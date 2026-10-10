package com.flexy.f1live.data

import android.util.Log
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.LiveSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class CompositeLiveTimingClient(
    private val primary: LiveTimingClient,
    private val fallback: LiveTimingClient,
    private val forbiddenFailureThreshold: Int = 1,
    private val anyFailureThreshold: Int = 3,
    private val snapshotTimeoutMs: Long = 20_000L,
    private val initialSnapshotTimeoutMs: Long = 2_500L,
    private val watchdogIntervalMs: Long = 250L,
    private val cache: CachedSessionSource? = null,
    private val log: (message: String, error: Throwable) -> Unit = ::logWarning,
) : LiveTimingClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(LiveSessionState.EMPTY)
    override val state: StateFlow<LiveSessionState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<Throwable>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val errors: Flow<Throwable> = _errors.asSharedFlow()

    private val lock = Any()
    private val publishLock = Any()
    private val jobs = mutableListOf<Job>()
    private var fallbackJobs: List<Job> = emptyList()
    private var running = false

    private val fallbackActive = AtomicBoolean(false)

    @Volatile
    private var fallbackFailed = false

    @Volatile
    private var forbiddenFailures = 0

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var lastPrimarySnapshotAt = 0L

    @Volatile
    private var primaryDelivered = false

    override val isRunning: Boolean
        get() = primary.isRunning || fallback.isRunning

    // ------------------------------------------------------------------ lifecycle

    override fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            forbiddenFailures = 0
            consecutiveFailures = 0
            primaryDelivered = false
            fallbackFailed = false
            lastPrimarySnapshotAt = System.currentTimeMillis()

            cache?.peek()?.let(::publish)

            val subscribed = CompletableDeferred<Unit>()
            jobs += scope.launch {
                primary.errors.signalWhenSubscribed(subscribed).collect(::onPrimaryError)
            }
            jobs += scope.launch { primary.state.collect(::onPrimaryState) }
            if (cache != null) jobs += scope.launch { cache.read()?.let(::publish) }
            jobs += scope.launch {
                subscribed.await()
                if (synchronized(lock) { running }) primary.start()
            }
            jobs += scope.launch { watchdog() }
        }
    }

    override fun stop() {
        val cancelled = synchronized(lock) {
            running = false
            val current = jobs.toList() + fallbackJobs
            jobs.clear()
            fallbackJobs = emptyList()
            current
        }
        cancelled.forEach { it.cancel() }
        fallbackActive.set(false)
        primary.stop()
        fallback.stop()
        synchronized(publishLock) { _state.value = LiveSessionState.EMPTY }
    }

    // ------------------------------------------------------------------ publishing

    private fun publish(candidate: LiveSessionState) {
        synchronized(publishLock) {
            _state.value = preferState(_state.value, candidate)
        }
    }

    // ------------------------------------------------------------------- primary

    private fun onPrimaryError(error: Throwable) {
        log("F1 live timing: " + (error.message ?: error.toString()), error)
        consecutiveFailures += 1
        val message = (error.message ?: "") + " " + (error.cause?.message ?: "")
        if (message.contains("403")) forbiddenFailures += 1 else forbiddenFailures = 0

        if (forbiddenFailures >= forbiddenFailureThreshold || consecutiveFailures >= anyFailureThreshold) {
            startFallback()
        }
        surfaceIfBothDown(error)
    }

    private fun onFallbackError(error: Throwable) {
        log(ESPN_ERROR_PREFIX + (error.message ?: error.toString()), error)
        fallbackFailed = true
        surfaceIfBothDown(error)
    }

    private fun surfaceIfBothDown(cause: Throwable) {
        if (!fallbackActive.get() || !fallbackFailed) return
        if (_state.value.drivers.isNotEmpty()) return
        _errors.tryEmit(IOException(BOTH_DOWN_MESSAGE, cause))
    }

    private fun onPrimaryState(state: LiveSessionState) {
        val usable = state.source == LiveSource.F1_LIVE_TIMING && state.drivers.isNotEmpty()
        if (usable) {
            forbiddenFailures = 0
            consecutiveFailures = 0
            primaryDelivered = true
            lastPrimarySnapshotAt = System.currentTimeMillis()
            stopFallback()
        }
        if (usable || !fallbackActive.get()) publish(state)
    }

    private suspend fun watchdog() {
        while (currentCoroutineContext().isActive) {
            delay(watchdogIntervalMs)
            if (fallbackActive.get()) continue
            val window = if (primaryDelivered) {
                snapshotTimeoutMs
            } else {
                minOf(initialSnapshotTimeoutMs, snapshotTimeoutMs)
            }
            if (System.currentTimeMillis() - lastPrimarySnapshotAt >= window) startFallback()
        }
    }

    // ------------------------------------------------------------------ fallback

    private fun startFallback() {
        if (!fallbackActive.compareAndSet(false, true)) return
        fallbackFailed = false
        synchronized(lock) {
            if (!running) {
                fallbackActive.set(false)
                return
            }
            fallbackJobs = listOf(
                scope.launch { fallback.errors.collect(::onFallbackError) },
                scope.launch {
                    fallback.state.collect { state ->
                        if (!fallbackActive.get()) return@collect
                        if (state.drivers.isNotEmpty()) fallbackFailed = false
                        publish(state)
                    }
                },
            )
        }
        fallback.start()
    }

    private fun stopFallback() {
        if (!fallbackActive.compareAndSet(true, false)) return
        synchronized(lock) {
            fallbackJobs.forEach { it.cancel() }
            fallbackJobs = emptyList()
        }
        fallback.stop()
    }

    private fun Flow<Throwable>.signalWhenSubscribed(signal: CompletableDeferred<Unit>): Flow<Throwable> =
        if (this is SharedFlow<Throwable>) {
            onSubscription { signal.complete(Unit) }
        } else {
            onStart { signal.complete(Unit) }
        }

    companion object {
        private const val ESPN_ERROR_PREFIX = "ESPN fallback: "

        const val BOTH_DOWN_MESSAGE = "F1 live timing and ESPN are both unreachable"
    }
}

private fun logWarning(message: String, error: Throwable) {
    runCatching { Log.w("Laply", message, error) }
}

internal fun preferState(current: LiveSessionState, next: LiveSessionState): LiveSessionState {
    if (next == current) return current
    if (next.drivers.isEmpty()) return if (current.drivers.isEmpty()) next else current
    if (current.drivers.isEmpty()) return next
    if (current.isLive != next.isLive) {
        if (next.isLive) return next
        val sessionEnded = next.source != LiveSource.CACHE && isSameSession(current, next)
        return if (sessionEnded) next else current
    }
    if (next.source == LiveSource.CACHE) return current
    if (current.source == LiveSource.CACHE) {
        if (next.source == LiveSource.F1_LIVE_TIMING) return next
        val sameSession = isSameSession(current, next)
        return if (!sameSession || informationScore(next) >= informationScore(current)) next else current
    }
    return next
}

private fun isSameSession(a: LiveSessionState, b: LiveSessionState): Boolean =
    a.meetingName.trim().equals(b.meetingName.trim(), ignoreCase = true) &&
        a.sessionName.trim().equals(b.sessionName.trim(), ignoreCase = true)

private fun informationScore(state: LiveSessionState): Int {
    var score = state.drivers.size + state.raceControl.size
    for (driver in state.drivers) {
        if (driver.bestLapTime.isNotBlank()) score += 1
        if (driver.lastLapTime.isNotBlank()) score += 1
        if (driver.gapToLeader.isNotBlank()) score += 1
        if (driver.sectors.any { it.value.isNotBlank() }) score += 1
    }
    return score
}
