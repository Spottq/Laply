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

/**
 * Runs the official F1 SignalR feed, falls back to ESPN when it cannot be reached, and paints the
 * last session off disk while both are still connecting.
 *
 * CloudFront blocks the F1 `negotiate` endpoint for whole networks - the phone sees
 * `negotiate failed: HTTP 403` on every attempt, including through some VPN exits - while ESPN's
 * public API answers fine. So the primary client stays running with its own backoff (the block is
 * often temporary or exit-node specific) and the fallback is switched on when the primary is
 * clearly not delivering:
 *
 *  * [forbiddenFailureThreshold] consecutive failures whose message mentions 403, or
 *  * [anyFailureThreshold] consecutive failures of any kind, or
 *  * [initialSnapshotTimeoutMs] elapsed since [start] without the first usable snapshot, or
 *  * [snapshotTimeoutMs] elapsed since the last usable snapshot once the primary has delivered.
 *
 * The thresholds are tuned for time-to-first-data. The primary answers within ~2.5 s when it works
 * (negotiate + socket + Subscribe completion), while a CloudFront block answers `403` in under a
 * second and a DPI black hole never answers at all. Waiting for a second 403 (1 s backoff + another
 * round trip) or a 20 s silence before even starting ESPN used to cost 3-30 s of an empty screen;
 * now ESPN starts on the first 403 or after [initialSnapshotTimeoutMs], and the primary still takes
 * over the moment it delivers, so a healthy network never shows ESPN for long, if at all.
 *
 * Whatever the source, everything reaches [state] through [preferState], which is the whole answer
 * to the screen flickering between a classification and "No live session": a snapshot without
 * drivers never replaces one that has them, and a cached session is only displaced by a network
 * one that is live, newer, or carries more information. Both feeds emit driver-less snapshots
 * routinely - the SignalR client resets to EMPTY on every reconnect, and the ESPN scoreboard
 * publishes a competition minutes before it lists its competitors - and each of those used to land
 * on screen verbatim.
 *
 * Errors: a failing source is only logged (tag `Laply`) - one feed down while the other one, or
 * the cache, still has something to show is not the user's problem. [errors] emits only when both
 * feeds have failed and there is nothing at all on screen, which is the one case the Live tab turns
 * into an error with a Reconnect button.
 */
class CompositeLiveTimingClient(
    private val primary: LiveTimingClient,
    private val fallback: LiveTimingClient,
    private val forbiddenFailureThreshold: Int = 1,
    private val anyFailureThreshold: Int = 3,
    private val snapshotTimeoutMs: Long = 20_000L,
    /**
     * Head start the primary gets before the fallback is raced against it. Only applies until its
     * first usable snapshot; after that the far more patient [snapshotTimeoutMs] takes over, because
     * an idle-but-healthy feed only heartbeats every few seconds.
     */
    private val initialSnapshotTimeoutMs: Long = 2_500L,
    private val watchdogIntervalMs: Long = 250L,
    /** The last finished session, replayed before any network call; null disables the replay. */
    private val cache: CachedSessionSource? = null,
    /** Where per-source failures go instead of the screen; replaced in unit tests. */
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

    /** The fallback has failed since it last delivered a classification (or since it started). */
    @Volatile
    private var fallbackFailed = false

    @Volatile
    private var forbiddenFailures = 0

    @Volatile
    private var consecutiveFailures = 0

    /** elapsedRealtime-ish stamp of the last usable primary snapshot, or of [start]. */
    @Volatile
    private var lastPrimarySnapshotAt = 0L

    /** Whether the primary has produced a usable snapshot since [start]; picks the watchdog window. */
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

            // Cheapest first: if the cache was preloaded, the classification is on screen before
            // this method returns and the Live tab never shows "No live session" at start-up.
            cache?.peek()?.let(::publish)

            // The primary's error flow drops on overflow, so subscribe *before* connecting:
            // the very first negotiate 403 must not be missed.
            val subscribed = CompletableDeferred<Unit>()
            jobs += scope.launch {
                primary.errors.signalWhenSubscribed(subscribed).collect(::onPrimaryError)
            }
            jobs += scope.launch { primary.state.collect(::onPrimaryState) }
            // The disk read and the connection run side by side: the network needs a second or more
            // for its first byte, so the cache still lands first in practice, and when it does not,
            // preferState already refuses to let a cached session overwrite a network one. Chaining
            // them only put a cold-start JSON decode in front of the negotiate request.
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
        // The only place a populated state is dropped: an explicit stop.
        synchronized(publishLock) { _state.value = LiveSessionState.EMPTY }
    }

    // ------------------------------------------------------------------ publishing

    /** Single funnel for every source; [preferState] decides whether the screen changes. */
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

    /**
     * The only way an error reaches [errors]: the primary is not delivering (the fallback only runs
     * then), the fallback has failed too, and there is no classification on screen - not even the
     * cached one.
     */
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
        // While ESPN is on screen the primary's empty/disconnected states must not wipe it, and
        // even without the fallback a reconnect's EMPTY is filtered out by preferState.
        if (usable || !fallbackActive.get()) publish(state)
    }

    /**
     * Nothing usable from the primary within the current window: assume the network blocks it.
     * Before the first snapshot that window is [initialSnapshotTimeoutMs] (never longer than
     * [snapshotTimeoutMs]), so a black-holed connect no longer holds the screen for 20 s.
     */
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

    /**
     * Completes [signal] once this flow is actually subscribed - [onSubscription] guarantees it for
     * a [SharedFlow], [onStart] is the best a cold flow can do.
     */
    private fun Flow<Throwable>.signalWhenSubscribed(signal: CompletableDeferred<Unit>): Flow<Throwable> =
        if (this is SharedFlow<Throwable>) {
            onSubscription { signal.complete(Unit) }
        } else {
            onStart { signal.complete(Unit) }
        }

    companion object {
        private const val ESPN_ERROR_PREFIX = "ESPN fallback: "

        /** Message of the one error [errors] emits: both feeds down, nothing to show. */
        const val BOTH_DOWN_MESSAGE = "F1 live timing and ESPN are both unreachable"
    }
}

/** Logcat in the app; a no-op under plain JVM unit tests, where android.util.Log is a stub. */
private fun logWarning(message: String, error: Throwable) {
    runCatching { Log.w("Laply", message, error) }
}

/**
 * Which of two snapshots the screen should show. Pure, so the rules are unit tested directly.
 *
 * In order:
 *  1. an empty classification never replaces a populated one (this is the anti-flicker rule);
 *  2. anything beats nothing;
 *  3. a live session beats a finished one, and is only displaced by a finished one when the network
 *     reports that very session as over - the chequered flag. Some other finished session (ESPN's
 *     last completed one while its live roster blinks out, the disk cache) never ends it;
 *  4. the disk cache yields to the official feed always, and to any other network source when it
 *     is a *different* session or one that actually knows more (lap times, gaps, more cars), so a
 *     thinner ESPN copy of the same classification does not overwrite a richer stored one;
 *  5. the cache never overwrites something the network produced.
 */
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
        // The official feed is the full record of the session it is on: it always takes over from
        // the disk copy. Only a thinner source (ESPN has no sector or lap cells) has to prove it
        // knows at least as much - otherwise, in a qualifying break, the stored copy of the part
        // just finished outscored the next part's half-filled timing and stayed on screen.
        if (next.source == LiveSource.F1_LIVE_TIMING) return next
        val sameSession = isSameSession(current, next)
        return if (!sameSession || informationScore(next) >= informationScore(current)) next else current
    }
    return next
}

private fun isSameSession(a: LiveSessionState, b: LiveSessionState): Boolean =
    a.meetingName.trim().equals(b.meetingName.trim(), ignoreCase = true) &&
        a.sessionName.trim().equals(b.sessionName.trim(), ignoreCase = true)

/** How much a snapshot actually says: cars, timing cells filled in, race-control lines. */
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
