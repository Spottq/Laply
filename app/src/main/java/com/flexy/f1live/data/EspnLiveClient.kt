package com.flexy.f1live.data

import android.util.Log
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.LiveSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EspnLiveClient(
    httpClient: OkHttpClient,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val detailIntervalMs: Long = DETAIL_INTERVAL_MS,
    private val seasonIntervalMs: Long = SEASON_INTERVAL_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : LiveTimingClient {

    private val http: OkHttpClient = httpClient

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

    override val isRunning: Boolean
        get() = synchronized(lock) { job?.isActive == true }

    override fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch { pollLoop() }
        }
    }

    override fun stop() {
        val previous = synchronized(lock) {
            val current = job
            job = null
            current
        }
        previous?.cancel()
        _state.value = LiveSessionState.EMPTY
    }

    private fun publish(candidate: LiveSessionState) {
        if (candidate.drivers.isEmpty() && _state.value.drivers.isNotEmpty()) return
        _state.value = candidate
    }

    // ------------------------------------------------------------------- polling

    private suspend fun pollLoop() {
        var cache = Cache()
        var retryMs = FIRST_RETRY_MS
        while (currentCoroutineContext().isActive) {
            val wait = try {
                cache = tick(cache)
                retryMs = FIRST_RETRY_MS
                if (cache.isLive) pollIntervalMs else seasonIntervalMs
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "ESPN poll failed", error)
                _errors.tryEmit(error)
                val interval = if (cache.isLive) pollIntervalMs else seasonIntervalMs
                retryMs.coerceAtMost(interval).also { retryMs = (retryMs * 2).coerceAtMost(interval) }
            }
            delay(wait)
        }
    }

    private data class Cache(
        val competitionId: String? = null,
        val fetchedAtMillis: Long = 0L,
        val isLive: Boolean = false,
        val vehicles: Map<String, EspnMapper.Vehicle> = emptyMap(),
        val statistics: Map<String, Map<String, String>> = emptyMap(),
        val statuses: Map<String, EspnMapper.CompetitorStatus> = emptyMap(),
        val lastCompleted: EspnMapper.Competition? = null,
        val seasonFetchedAtMillis: Long = 0L,
        val lapsByCircuit: Map<String, Int> = emptyMap(),
    )

    private suspend fun tick(cached: Cache): Cache = coroutineScope {
        val now = nowMillis()
        val seasonDue = cached.lastCompleted == null ||
            now - cached.seasonFetchedAtMillis >= seasonIntervalMs
        val speculativeSeason = if (seasonDue && cached.competitionId == null) {
            async { runCatching { getJson(seasonScoreboardUrl(now)) } }
        } else {
            null
        }

        val live = EspnMapper.pickLive(EspnMapper.parseScoreboard(getJson(SCOREBOARD_URL)))
            ?.takeIf { it.athletes.isNotEmpty() }

        var lastCompleted = cached.lastCompleted
        var seasonFetchedAt = cached.seasonFetchedAtMillis
        if (live == null && seasonDue) {
            val season = speculativeSeason?.await()?.getOrThrow() ?: getJson(seasonScoreboardUrl(now))
            lastCompleted = EspnMapper.pickLastCompleted(EspnMapper.parseScoreboard(season))
            seasonFetchedAt = now
        } else {
            speculativeSeason?.cancel()
        }

        val competition = live ?: lastCompleted
        if (competition == null) {
            publish(LiveSessionState.EMPTY.copy(isConnected = true, source = LiveSource.ESPN))
            return@coroutineScope cached.copy(isLive = false, seasonFetchedAtMillis = seasonFetchedAt)
        }

        val competitionStatusJob = async {
            runCatching {
                EspnMapper.parseCompetitionStatus(getJson(competitionStatusUrl(competition)))
            }.getOrNull()
        }

        val lapsJob = async { cached.lapsByCircuit.withCircuitLaps(competition) }

        val stale = cached.competitionId != competition.competitionId ||
            cached.vehicles.isEmpty() ||
            (live != null && now - cached.fetchedAtMillis >= detailIntervalMs)
        val details = if (stale) fetchDetails(competition, now) else cached
        val competitionStatus = competitionStatusJob.await()
        val lapsByCircuit = lapsJob.await()

        publish(
            EspnMapper.buildState(
                competition = competition,
                vehicles = details.vehicles,
                statistics = details.statistics,
                statuses = details.statuses,
                nowUtcMillis = now,
                competitionStatus = competitionStatus,
                totalLaps = lapsByCircuit[competition.circuitId],
            ),
        )
        Cache(
            competitionId = competition.competitionId,
            fetchedAtMillis = details.fetchedAtMillis,
            isLive = live != null,
            vehicles = details.vehicles,
            statistics = details.statistics,
            statuses = details.statuses,
            lastCompleted = lastCompleted,
            seasonFetchedAtMillis = seasonFetchedAt,
            lapsByCircuit = lapsByCircuit,
        )
    }

    private suspend fun Map<String, Int>.withCircuitLaps(
        competition: EspnMapper.Competition,
    ): Map<String, Int> {
        val id = competition.circuitId
        if (id.isBlank() || containsKey(id)) return this
        val laps = runCatching { EspnMapper.parseCircuitLaps(getJson(circuitUrl(id))) }.getOrNull()
            ?: return this
        return this + (id to laps)
    }

    private suspend fun fetchDetails(
        competition: EspnMapper.Competition,
        nowUtcMillis: Long,
    ): Cache = coroutineScope {
        val vehiclesJob = async {
            EspnMapper.parseCompetitors(
                getJson(competitorsUrl(competition.eventId, competition.competitionId)),
            ).associateBy { it.competitorId }
        }
        val ids = competition.athletes.map { it.competitorId }.distinct()
        val gate = Semaphore(MAX_PARALLEL_REQUESTS)

        val statisticsJobs = ids.map { id ->
            async {
                id to gate.withPermit {
                    runCatching {
                        EspnMapper.parseStatistics(getJson(competitorUrl(competition, id, "statistics")))
                    }.getOrNull()
                }
            }
        }
        val statusJobs = ids.map { id ->
            async {
                id to gate.withPermit {
                    runCatching {
                        EspnMapper.parseCompetitorStatus(getJson(competitorUrl(competition, id, "status")))
                    }.getOrNull()
                }
            }
        }

        val byId = vehiclesJob.await()
        val statistics = statisticsJobs.awaitAll()
            .mapNotNull { (id, stats) -> stats?.let { id to it } }.toMap()
        val statuses = statusJobs.awaitAll()
            .mapNotNull { (id, status) -> status?.let { id to it } }.toMap()

        Cache(
            competitionId = competition.competitionId,
            fetchedAtMillis = nowUtcMillis,
            vehicles = byId,
            statistics = statistics,
            statuses = statuses,
        )
    }

    // ---------------------------------------------------------------------- http

    private suspend fun getJson(url: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val body = http.newCall(request).await()
        return json.parseToJsonElement(body) as? JsonObject
            ?: throw IOException("ESPN response is not a JSON object: $url")
    }

    private suspend fun Call.await(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        continuation.resumeWithException(
                            IOException("ESPN request failed: HTTP ${it.code} ${call.request().url}"),
                        )
                        return
                    }
                    continuation.resume(it.body.string())
                }
            }
        })
    }

    private companion object {
        const val TAG = "EspnLive"

        const val SCOREBOARD_URL =
            "https://site.web.api.espn.com/apis/site/v2/sports/racing/f1/scoreboard"
        const val CORE_BASE = "https://sports.core.api.espn.com/v2/sports/racing/leagues/f1/events"
        const val CIRCUIT_BASE = "https://sports.core.api.espn.com/v2/sports/racing/leagues/f1/circuits"

        const val USER_AGENT = "Mozilla/5.0"

        const val POLL_INTERVAL_MS = 8_000L
        const val DETAIL_INTERVAL_MS = 30_000L
        const val SEASON_INTERVAL_MS = 60_000L
        const val MAX_PARALLEL_REQUESTS = 16

        const val FIRST_RETRY_MS = 2_000L

        fun seasonScoreboardUrl(nowUtcMillis: Long): String {
            val year = Instant.ofEpochMilli(nowUtcMillis).atZone(ZoneOffset.UTC).year
            return "$SCOREBOARD_URL?dates=$year"
        }

        fun competitionStatusUrl(competition: EspnMapper.Competition): String =
            "$CORE_BASE/${competition.eventId}/competitions/${competition.competitionId}/status"

        fun circuitUrl(circuitId: String): String = "$CIRCUIT_BASE/$circuitId"

        fun competitorsUrl(eventId: String, competitionId: String): String =
            "$CORE_BASE/$eventId/competitions/$competitionId/competitors?limit=30"

        fun competitorUrl(
            competition: EspnMapper.Competition,
            competitorId: String,
            document: String,
        ): String = "$CORE_BASE/${competition.eventId}/competitions/" +
            "${competition.competitionId}/competitors/$competitorId/$document"
    }
}
