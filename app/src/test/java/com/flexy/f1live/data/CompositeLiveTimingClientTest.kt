package com.flexy.f1live.data

import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.LiveSource
import com.flexy.f1live.model.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class CompositeLiveTimingClientTest {

    private val primary = FakeLiveTimingClient()
    private val fallback = FakeLiveTimingClient()
    private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var composite: CompositeLiveTimingClient? = null
    private val logged = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        composite?.stop()
        observers.coroutineContext[Job]?.cancel()
    }

    private fun client(
        forbiddenFailureThreshold: Int = 2,
        anyFailureThreshold: Int = 5,
        snapshotTimeoutMs: Long = 60_000L,
        initialSnapshotTimeoutMs: Long = 60_000L,
        watchdogIntervalMs: Long = 20L,
        cache: CachedSessionSource? = null,
    ): CompositeLiveTimingClient = CompositeLiveTimingClient(
        primary = primary,
        fallback = fallback,
        forbiddenFailureThreshold = forbiddenFailureThreshold,
        anyFailureThreshold = anyFailureThreshold,
        snapshotTimeoutMs = snapshotTimeoutMs,
        initialSnapshotTimeoutMs = initialSnapshotTimeoutMs,
        watchdogIntervalMs = watchdogIntervalMs,
        cache = cache,
        log = { message, _ -> logged += message },
    ).also { composite = it }

    @Test
    fun `two 403s switch to the fallback and a real primary snapshot switches back`() = runBlocking {
        val composite = client()
        composite.start()
        awaitUntil("primary started") { primary.startCount == 1 }

        primary.fail("negotiate failed: HTTP 403")
        Thread.sleep(50)
        assertFalse("one 403 is not enough", fallback.isRunning)

        primary.fail("negotiate failed: HTTP 403")
        awaitUntil("fallback started") { fallback.isRunning }
        assertEquals(1, fallback.startCount)
        assertTrue("the primary keeps retrying in the background", primary.isRunning)

        fallback.push(espnState)
        awaitUntil("espn state mirrored") { composite.state.value.source == LiveSource.ESPN }
        primary.push(LiveSessionState.EMPTY)
        primary.fail("negotiate failed: HTTP 403")
        Thread.sleep(50)
        assertEquals(LiveSource.ESPN, composite.state.value.source)
        assertEquals(2, composite.state.value.drivers.size)

        primary.push(f1State)
        awaitUntil("fallback stopped") { !fallback.isRunning }
        assertEquals(LiveSource.F1_LIVE_TIMING, composite.state.value.source)
        assertEquals(1, fallback.stopCount)
    }

    @Test
    fun `five failures of any kind switch to the fallback`() = runBlocking {
        val composite = client()
        composite.start()
        awaitUntil("primary started") { primary.startCount == 1 }

        repeat(4) { primary.fail("socket closed unexpectedly") }
        Thread.sleep(50)
        assertFalse(fallback.isRunning)

        primary.fail("socket closed unexpectedly")
        awaitUntil("fallback started") { fallback.isRunning }
    }

    @Test
    fun `silence for longer than the snapshot timeout switches to the fallback`() = runBlocking {
        val composite = client(snapshotTimeoutMs = 150L, watchdogIntervalMs = 20L)
        composite.start()
        awaitUntil("primary started") { primary.startCount == 1 }
        assertFalse(fallback.isRunning)

        awaitUntil("watchdog fired") { fallback.isRunning }
    }

    // ------------------------------------------------------- time to first data

    @Test
    fun `with the default thresholds a single 403 starts the fallback`() = runBlocking {
        val composite = CompositeLiveTimingClient(primary = primary, fallback = fallback)
            .also { this@CompositeLiveTimingClientTest.composite = it }
        composite.start()
        awaitUntil("primary started") { primary.startCount == 1 }

        primary.fail("negotiate failed: HTTP 403")

        awaitUntil("fallback started on the first 403") { fallback.isRunning }
        assertTrue("the primary keeps retrying in the background", primary.isRunning)
    }

    @Test
    fun `a primary that never answers is raced by the fallback after the short first window`() =
        runBlocking {
            val composite = client(snapshotTimeoutMs = 60_000L, initialSnapshotTimeoutMs = 150L)
            composite.start()
            awaitUntil("primary started") { primary.startCount == 1 }
            assertFalse(fallback.isRunning)

            awaitUntil("first-snapshot window fired") { fallback.isRunning }

            primary.push(f1State)
            awaitUntil("fallback stopped") { !fallback.isRunning }
            assertEquals(LiveSource.F1_LIVE_TIMING, composite.state.value.source)
        }

    @Test
    fun `once the primary has delivered the patient window applies`() = runBlocking {
        val composite = client(snapshotTimeoutMs = 60_000L, initialSnapshotTimeoutMs = 100L)
        composite.start()
        awaitUntil("primary started") { primary.startCount == 1 }
        primary.push(f1State)
        awaitUntil("live state on screen") { composite.state.value.source == LiveSource.F1_LIVE_TIMING }

        Thread.sleep(400)
        assertFalse(fallback.isRunning)
    }

    @Test
    fun `a slow cache read does not hold back the network`() = runBlocking {
        val composite = client(cache = FakeCache(cachedState, preloaded = false, readDelayMs = 1_000L))
        composite.start()

        awaitUntil("primary started while the cache is still loading", timeoutMs = 500L) {
            primary.startCount == 1
        }
        primary.push(f1State)
        awaitUntil("live state on screen") { composite.state.value.source == LiveSource.F1_LIVE_TIMING }

        Thread.sleep(1_200)
        assertEquals(LiveSource.F1_LIVE_TIMING, composite.state.value.source)
    }

    @Test
    fun `a primary snapshot keeps the watchdog quiet`() = runBlocking {
        val composite = client(snapshotTimeoutMs = 200L, watchdogIntervalMs = 20L)
        composite.start()
        awaitUntil("primary started") { primary.startCount == 1 }

        repeat(6) {
            primary.push(f1State.copy(lastUpdateUtcMillis = System.currentTimeMillis()))
            Thread.sleep(40)
        }
        assertFalse("a healthy primary never triggers the fallback", fallback.isRunning)
        assertEquals(LiveSource.F1_LIVE_TIMING, composite.state.value.source)
    }

    @Test
    fun `one feed failing is only logged while the other one delivers`() = runBlocking {
        val composite = client()
        val seen = CopyOnWriteArrayList<String>()
        observers.launch { composite.errors.collect { seen += it.message.orEmpty() } }
        Thread.sleep(50)

        composite.start()
        awaitUntil("primary started") { primary.startCount == 1 }
        repeat(2) { primary.fail("negotiate failed: HTTP 403") }
        awaitUntil("fallback started") { fallback.isRunning }
        awaitUntil("primary error logged") { logged.any { it.contains("HTTP 403") } }

        fallback.push(espnState)
        awaitUntil("espn state mirrored") { composite.state.value.source == LiveSource.ESPN }
        awaitUntil("espn error logged", timeoutMs = 3_000L) {
            fallback.failNow("ESPN request failed: HTTP 500")
            logged.any { it.startsWith("ESPN fallback: ") && it.contains("HTTP 500") }
        }
        Thread.sleep(100)
        assertTrue("nothing surfaced: $seen", seen.isEmpty())
    }

    @Test
    fun `both feeds down with nothing to show surfaces one error`() = runBlocking {
        val composite = client()
        val seen = CopyOnWriteArrayList<String>()
        observers.launch { composite.errors.collect { seen += it.message.orEmpty() } }
        Thread.sleep(50)

        composite.start()
        awaitUntil("primary started") { primary.startCount == 1 }
        repeat(2) { primary.fail("negotiate failed: HTTP 403") }
        awaitUntil("fallback started") { fallback.isRunning }
        assertTrue("the primary alone failing is not surfaced", seen.isEmpty())

        awaitUntil("both-down error surfaced", timeoutMs = 3_000L) {
            fallback.failNow("ESPN request failed: HTTP 500")
            seen.any { it == CompositeLiveTimingClient.BOTH_DOWN_MESSAGE }
        }
    }

    @Test
    fun `a cached session keeps a double failure off the screen`() = runBlocking {
        val composite = client(cache = FakeCache(cachedState))
        val seen = CopyOnWriteArrayList<String>()
        observers.launch { composite.errors.collect { seen += it.message.orEmpty() } }
        Thread.sleep(50)

        composite.start()
        awaitUntil("primary started") { primary.startCount == 1 }
        repeat(2) { primary.fail("negotiate failed: HTTP 403") }
        awaitUntil("fallback started") { fallback.isRunning }
        awaitUntil("espn error logged", timeoutMs = 3_000L) {
            fallback.failNow("ESPN request failed: HTTP 500")
            logged.any { it.contains("HTTP 500") }
        }
        Thread.sleep(100)
        assertEquals(LiveSource.CACHE, composite.state.value.source)
        assertTrue("nothing surfaced: $seen", seen.isEmpty())
    }

    @Test
    fun `stop shuts both feeds down and clears the state`() = runBlocking {
        val composite = client()
        composite.start()
        awaitUntil("primary started") { primary.startCount == 1 }
        repeat(2) { primary.fail("negotiate failed: HTTP 403") }
        awaitUntil("fallback started") { fallback.isRunning }
        fallback.push(espnState)
        awaitUntil("espn state mirrored") { composite.state.value.source == LiveSource.ESPN }

        composite.stop()

        assertFalse(primary.isRunning)
        assertFalse(fallback.isRunning)
        assertEquals(LiveSessionState.EMPTY, composite.state.value)
        assertFalse(composite.isRunning)
    }

    // ------------------------------------------------- cached first, no empty overrides

    @Test
    fun `the cached session is on screen before the network is even started`() = runBlocking {
        val cache = FakeCache(cachedState)
        val composite = client(cache = cache)

        composite.start()

        assertEquals(LiveSource.CACHE, composite.state.value.source)
        assertEquals(2, composite.state.value.drivers.size)
        awaitUntil("primary started") { primary.startCount == 1 }
    }

    @Test
    fun `a cache that was not preloaded is still published before the feed connects`() = runBlocking {
        val cache = FakeCache(cachedState, preloaded = false)
        val composite = client(cache = cache)

        composite.start()

        awaitUntil("cached state published") { composite.state.value.source == LiveSource.CACHE }
        awaitUntil("primary started after the cache") { primary.startCount == 1 }
    }

    @Test
    fun `no empty state ever overrides a populated one`() = runBlocking {
        val composite = client(cache = FakeCache(cachedState))
        composite.start()
        awaitUntil("primary started") { primary.startCount == 1 }

        primary.push(LiveSessionState.EMPTY)
        primary.push(LiveSessionState.EMPTY.copy(isConnected = true, source = LiveSource.F1_LIVE_TIMING))
        Thread.sleep(50)
        assertEquals(2, composite.state.value.drivers.size)

        primary.push(f1State)
        awaitUntil("live state on screen") { composite.state.value.source == LiveSource.F1_LIVE_TIMING }
        primary.push(LiveSessionState.EMPTY)
        Thread.sleep(50)
        assertEquals(3, composite.state.value.drivers.size)
        assertTrue(composite.state.value.isLive)

        composite.stop()
        assertEquals(LiveSessionState.EMPTY, composite.state.value)
    }

    @Test
    fun `a live network session replaces the cached finished one`() = runBlocking {
        val composite = client(cache = FakeCache(cachedState))
        composite.start()
        awaitUntil("cached state published") { composite.state.value.source == LiveSource.CACHE }

        primary.push(f1State)

        awaitUntil("live state wins") { composite.state.value.source == LiveSource.F1_LIVE_TIMING }
        assertTrue(composite.state.value.isLive)
    }

    @Test
    fun `preferState keeps the richer copy of the same finished session`() {
        val thin = espnState.copy(
            status = SessionStatus.FINISHED,
            drivers = espnState.drivers.map { it.copy(bestLapTime = "") },
        )
        val rich = cachedState

        assertEquals(rich, preferState(rich, thin))
        val richer = rich.copy(
            source = LiveSource.ESPN,
            drivers = rich.drivers.map { it.copy(gapToLeader = "+0.263") },
        )
        assertEquals(richer, preferState(rich, richer))
        val other = thin.copy(meetingName = "Italian Grand Prix")
        assertEquals(other, preferState(rich, other))
        assertEquals(other, preferState(other, rich))
    }

    @Test
    fun `the chequered flag ends the live session on screen`() = runBlocking {
        val composite = client()
        composite.start()
        primary.push(f1State)
        awaitUntil("live state published") { composite.state.value.isLive }

        primary.push(f1State.copy(status = SessionStatus.FINISHED))

        awaitUntil("finish published") { composite.state.value.status == SessionStatus.FINISHED }
    }

    @Test
    fun `preferState lets only the same session end a live one`() {
        val live = espnState
        val finished = live.copy(status = SessionStatus.FINALISED)
        assertEquals(finished, preferState(live, finished))
        val earlier = finished.copy(sessionName = "Practice 3")
        assertEquals(live, preferState(live, earlier))
        val cached = finished.copy(source = LiveSource.CACHE)
        assertEquals(live, preferState(live, cached))
    }

    @Test
    fun `the official feed always takes over from the disk copy`() {
        val thinLive = f1State.copy(
            meetingName = cachedState.meetingName,
            status = SessionStatus.FINISHED,
            drivers = listOf(driver(1, "RUS")),
        )
        assertEquals(thinLive, preferState(cachedState, thinLive))
    }

    // ------------------------------------------------------------------ helpers

    private fun awaitUntil(what: String, timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("timed out waiting for: $what")
    }

    private val cachedState = LiveSessionState.EMPTY.copy(
        meetingName = "Dutch Grand Prix",
        sessionName = "Qualifying",
        status = SessionStatus.FINISHED,
        drivers = listOf(
            driver(1, "RUS").copy(bestLapTime = "1:08.662"),
            driver(2, "VER").copy(bestLapTime = "1:08.925"),
        ),
        source = LiveSource.CACHE,
    )

    private class FakeCache(
        private val state: LiveSessionState,
        preloaded: Boolean = true,
        private val readDelayMs: Long = 0L,
    ) : CachedSessionSource {
        private val memory = if (preloaded) state else null
        override fun peek(): LiveSessionState? = memory
        override suspend fun read(): LiveSessionState {
            if (readDelayMs > 0) delay(readDelayMs)
            return state
        }
    }

    private val espnState = LiveSessionState.EMPTY.copy(
        isConnected = true,
        meetingName = "Dutch Grand Prix",
        sessionName = "Qualifying",
        status = SessionStatus.STARTED,
        drivers = listOf(driver(1, "RUS"), driver(2, "VER")),
        source = LiveSource.ESPN,
    )

    private val f1State = LiveSessionState.EMPTY.copy(
        isConnected = true,
        sessionName = "Qualifying",
        status = SessionStatus.STARTED,
        drivers = listOf(driver(1, "RUS"), driver(2, "VER"), driver(3, "NOR")),
        source = LiveSource.F1_LIVE_TIMING,
    )

    private fun driver(position: Int, tla: String) = DriverTiming(
        position = position, racingNumber = position.toString(), tla = tla, firstName = "",
        lastName = tla, shortName = tla, teamName = "", teamColorHex = null, headshotUrl = null,
        countryCode = null, bestLapTime = "", lastLapTime = "", gapToLeader = "", interval = "",
        sectors = emptyList(), inPit = false, pitOut = false, retired = false, stopped = false,
        knockedOut = false, numberOfLaps = 0, numberOfPitStops = 0, tyreCompound = null,
    )

    private class FakeLiveTimingClient : LiveTimingClient {
        private val _state = MutableStateFlow(LiveSessionState.EMPTY)
        override val state: StateFlow<LiveSessionState> = _state.asStateFlow()

        private val _errors = MutableSharedFlow<Throwable>(
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val errors: Flow<Throwable> = _errors.asSharedFlow()

        @Volatile
        private var running = false
        override val isRunning: Boolean get() = running

        @Volatile
        var startCount = 0
            private set

        @Volatile
        var stopCount = 0
            private set

        override fun start() {
            startCount += 1
            running = true
        }

        override fun stop() {
            stopCount += 1
            running = false
            _state.value = LiveSessionState.EMPTY
        }

        suspend fun fail(message: String) {
            _errors.emit(IOException(message))
        }

        fun failNow(message: String): Boolean = _errors.tryEmit(IOException(message))

        fun push(state: LiveSessionState) {
            _state.value = state
        }
    }
}
