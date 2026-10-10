package com.flexy.f1live.data

import android.util.Log
import com.flexy.f1live.model.LiveSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

class F1SignalRClient(httpClient: OkHttpClient) : LiveTimingClient {

    private val http: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    // Not the socket's infinite read timeout: a stalled CloudFront edge would hang negotiate forever.
    private val negotiateHttp: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(NEGOTIATE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(NEGOTIATE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(LiveSessionState.EMPTY)
    override val state: StateFlow<LiveSessionState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<Throwable>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val errors: Flow<Throwable> = _errors.asSharedFlow()

    private val lock = Any()
    private var job: Job? = null
    private var socket: WebSocket? = null

    @Volatile
    private var snapshot: JsonObject = JsonObject(emptyMap())

    override val isRunning: Boolean
        get() = synchronized(lock) { job?.isActive == true }

    override fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch { connectionLoop() }
        }
    }

    override fun stop() {
        val previous = synchronized(lock) {
            val current = job
            job = null
            val ws = socket
            socket = null
            ws?.close(NORMAL_CLOSURE, null) ?: Unit
            ws?.cancel()
            current
        }
        previous?.cancel()
        snapshot = JsonObject(emptyMap())
        _state.value = LiveSessionState.EMPTY
    }

    // ------------------------------------------------------------- connection

    private suspend fun connectionLoop() {
        var backoffMs = MIN_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            try {
                connectOnce { backoffMs = MIN_BACKOFF_MS }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "live timing connection failed", error)
                _errors.tryEmit(error)
            }
            _state.update { it.copy(isConnected = false) }
            if (!currentCoroutineContext().isActive) return
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun connectOnce(onSnapshot: () -> Unit) = coroutineScope {
        val negotiation = withContext(Dispatchers.IO) { negotiate() }
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        val request = Request.Builder()
            .url(
                WS_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("id", negotiation.connectionToken)
                    .build(),
            )
            .apply { if (negotiation.cookie.isNotEmpty()) header("Cookie", negotiation.cookie) }
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .build()

        val ws = http.newWebSocket(request, SocketListener(events))
        synchronized(lock) { socket = ws }
        val dirty = Channel<Unit>(Channel.CONFLATED)
        try {
            ws.send(HANDSHAKE + RECORD_SEPARATOR)
            ws.send(SUBSCRIBE + RECORD_SEPARATOR)

            val emitter = launch { emitLoop(dirty) }
            val pinger = launch {
                while (isActive) {
                    delay(PING_INTERVAL_MS)
                    ws.send(PING + RECORD_SEPARATOR)
                }
            }
            try {
                for (event in events) {
                    when (event) {
                        is SocketEvent.Text -> handlePayload(event.text, ws, dirty, onSnapshot)
                        is SocketEvent.Closed -> {
                            event.cause?.let { throw it }
                            return@coroutineScope
                        }
                    }
                }
            } finally {
                pinger.cancel()
                emitter.cancel()
            }
        } finally {
            dirty.close()
            events.close()
            ws.cancel()
            synchronized(lock) { if (socket === ws) socket = null }
        }
    }

    private fun negotiate(): Negotiation {
        val request = Request.Builder()
            .url(NEGOTIATE_URL)
            .post(ByteArray(0).toRequestBody(null))
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Accept", "application/json")
            .build()
        negotiateHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("negotiate failed: HTTP ${response.code}")
            val body = response.body.string()
            val token = (json.parseToJsonElement(body) as? JsonObject)
                ?.get("connectionToken")?.primitiveOrNull()?.contentOrNull
                ?: throw IOException("negotiate response carries no connectionToken")
            return Negotiation(token, awsCookies(response))
        }
    }

    private fun awsCookies(response: Response): String =
        response.headers("Set-Cookie")
            .map { it.substringBefore(';').trim() }
            .filter { it.startsWith("AWSALB") }
            .joinToString("; ")

    // ---------------------------------------------------------------- messages

    private fun handlePayload(
        payload: String,
        ws: WebSocket,
        dirty: SendChannel<Unit>,
        onSnapshot: () -> Unit,
    ) {
        for (record in payload.split(RECORD_SEPARATOR)) {
            val trimmed = record.trim()
            if (trimmed.isEmpty()) continue
            val message = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
                ?: continue
            handleMessage(message, ws, dirty, onSnapshot)
        }
    }

    private fun handleMessage(
        message: JsonObject,
        ws: WebSocket,
        dirty: SendChannel<Unit>,
        onSnapshot: () -> Unit,
    ) {
        when (message["type"]?.primitiveOrNull()?.intOrNull) {
            null -> message["error"]?.primitiveOrNull()?.contentOrNull?.let {
                throw IOException("SignalR handshake rejected: $it")
            }
            INVOCATION -> handleInvocation(message, dirty)
            COMPLETION -> handleCompletion(message, dirty, onSnapshot)
            PING_TYPE -> ws.send(PING + RECORD_SEPARATOR)
            CLOSE -> throw IOException(
                "server closed the connection: " +
                    (message["error"]?.primitiveOrNull()?.contentOrNull ?: "no reason given"),
            )
            else -> Unit
        }
    }

    private fun handleCompletion(message: JsonObject, dirty: SendChannel<Unit>, onSnapshot: () -> Unit) {
        message["error"]?.primitiveOrNull()?.contentOrNull?.let {
            throw IOException("Subscribe failed: $it")
        }
        val result = message["result"] as? JsonObject ?: return
        snapshot = JsonObject(result.filterKeys { !it.isCompressedTopic() })
        onSnapshot()
        dirty.trySend(Unit)
    }

    private fun handleInvocation(message: JsonObject, dirty: SendChannel<Unit>) {
        if (message["target"]?.primitiveOrNull()?.contentOrNull != "feed") return
        val arguments = message["arguments"] as? JsonArray ?: return
        val topic = arguments.getOrNull(0)?.primitiveOrNull()?.contentOrNull ?: return
        if (topic.isCompressedTopic()) return
        val delta = arguments.getOrNull(1) ?: return
        snapshot = JsonMerge.mergeTopic(snapshot, topic, delta)
        dirty.trySend(Unit)
    }

    private suspend fun emitLoop(dirty: ReceiveChannel<Unit>) {
        for (signal in dirty) {
            publish()
            delay(THROTTLE_MS)
        }
    }

    private var lastPublishedAtMillis = 0L

    private fun publish() {
        val current = snapshot
        if (current.isEmpty()) return
        try {
            val parsed = LiveStateParser.parse(current, isConnected = true)
            val previous = _state.value
            val unchanged = parsed.copy(lastUpdateUtcMillis = previous.lastUpdateUtcMillis) == previous
            // Still re-emit now and then: the composite's watchdog reads a silent feed as blocked.
            if (unchanged && parsed.lastUpdateUtcMillis - lastPublishedAtMillis < HEARTBEAT_MS) return
            lastPublishedAtMillis = parsed.lastUpdateUtcMillis
            _state.value = parsed
        } catch (error: Throwable) {
            Log.w(TAG, "failed to parse live snapshot", error)
            _errors.tryEmit(error)
        }
    }

    // ----------------------------------------------------------------- plumbing

    private class Negotiation(val connectionToken: String, val cookie: String)

    private sealed interface SocketEvent {
        class Text(val text: String) : SocketEvent
        class Closed(val cause: Throwable?) : SocketEvent
    }

    private class SocketListener(private val events: SendChannel<SocketEvent>) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            events.trySend(SocketEvent.Text(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            events.trySend(SocketEvent.Closed(null))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            events.trySend(SocketEvent.Closed(null))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            events.trySend(SocketEvent.Closed(t))
        }
    }

    private fun JsonElement.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private fun String.isCompressedTopic(): Boolean = endsWith(".z", ignoreCase = true)

    companion object {
        private const val TAG = "F1SignalR"
        private const val RECORD_SEPARATOR = "\u001e"
        private const val NEGOTIATE_URL =
            "https://livetiming.formula1.com/signalrcore/negotiate?negotiateVersion=1"

        private const val WS_URL = "https://livetiming.formula1.com/signalrcore"
        private const val ORIGIN = "https://www.formula1.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/128.0.0.0 Safari/537.36"

        private const val HANDSHAKE = """{"protocol":"json","version":1}"""
        private const val PING = """{"type":6}"""
        private val TOPICS = listOf(
            "SessionInfo", "SessionStatus", "SessionData", "TrackStatus", "TimingData",
            "TimingAppData", "TimingStats", "DriverList", "LapCount", "RaceControlMessages",
            "WeatherData", "ExtrapolatedClock", "TopThree", "Heartbeat",
        )
        private val SUBSCRIBE = TOPICS.joinToString(
            prefix = """{"type":1,"invocationId":"1","target":"Subscribe","arguments":[[""",
            separator = ",",
            postfix = """]]}""",
        ) { "\"$it\"" }

        private const val INVOCATION = 1
        private const val COMPLETION = 3
        private const val PING_TYPE = 6
        private const val CLOSE = 7

        private const val NORMAL_CLOSURE = 1000
        private const val CONNECT_TIMEOUT_MS = 6_000L
        private const val NEGOTIATE_READ_TIMEOUT_MS = 8_000L
        private const val NEGOTIATE_CALL_TIMEOUT_MS = 12_000L
        private const val PING_INTERVAL_MS = 15_000L
        private const val THROTTLE_MS = 250L

        private const val HEARTBEAT_MS = 5_000L
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
