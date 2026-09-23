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

/**
 * Fallback live timing over ESPN's public racing API.
 *
 * The official F1 SignalR feed is fronted by CloudFront and answers `403 Request blocked` on a
 * number of networks (Russia among them, VPN or not). ESPN stays reachable there and publishes the
 * running order of the session in progress, so [CompositeLiveTimingClient] falls back to this
 * client when the primary feed cannot connect.
 *
 * It is a plain polling loop, no sockets. While a session is running the scoreboard is re-read every
 * [pollIntervalMs] (it is cached for ~7 s upstream anyway), which refreshes positions, and the far
 * more expensive per-driver documents - car numbers/teams plus one statistics and one status request
 * per competitor - only every [detailIntervalMs] or whenever the session changes.
 *
 * When nothing is running the client shows the results of the last completed session instead: the
 * season scoreboard (`?dates=YYYY`) is consulted every [seasonIntervalMs], its newest finished
 * competition is loaded once and published with [com.flexy.f1live.model.SessionStatus.FINISHED].
 *
 * Failures are reported on [errors] and the last good [state] is kept; the loop just keeps ticking.
 *
 * Time to first data is what a fallback is for, so the first tick is built for it: the live and
 * the season scoreboards are requested together, the per-driver documents are addressed straight
 * from the scoreboard's competitor ids (they do not wait for the competitors document), statistics
 * and statuses go out as one burst, and the competition status and lap count ride alongside it.
 * On a blocked network that took the first publish from ~9 s of serial round trips (scoreboard,
 * season, competitors, two driver bursts capped at 5 per host, status, circuit) to about three.
 */
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

    /**
     * A snapshot without drivers never replaces one that has them.
     *
     * ESPN answers with an empty roster far more often than it looks: between the 8 s scoreboard
     * tick and the 30 s statistics refresh, right after a session flips state, and whenever the
     * competitors document 404s for a competition that has not been seeded yet. Publishing those
     * verbatim made the screen alternate between the classification and an empty card every few
     * seconds; the composite applies the same rule one level up.
     */
    private fun publish(candidate: LiveSessionState) {
        if (candidate.drivers.isEmpty() && _state.value.drivers.isNotEmpty()) return
        _state.value = candidate
    }

    // ------------------------------------------------------------------- polling

    private suspend fun pollLoop() {
        var cache = Cache()
        var retryMs = FIRST_RETRY_MS
        while (currentCoroutineContext().isActive) {
            // Positions only move while a session runs; a finished one is re-checked far slower.
            val wait = try {
                cache = tick(cache)
                retryMs = FIRST_RETRY_MS
                if (cache.isLive) pollIntervalMs else seasonIntervalMs
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "ESPN poll failed", error)
                _errors.tryEmit(error)
                // A failed tick used to sleep the full season interval, so one dropped request on
                // the very first poll meant a minute of empty screen (and a failed poll during a
                // live session skipped seven refreshes). Retry soon, backing off up to the interval
                // the loop would have used anyway.
                val interval = if (cache.isLive) pollIntervalMs else seasonIntervalMs
                retryMs.coerceAtMost(interval).also { retryMs = (retryMs * 2).coerceAtMost(interval) }
            }
            delay(wait)
        }
    }

    /** What survives between ticks: the per-competition documents and the season lookup. */
    private data class Cache(
        val competitionId: String? = null,
        val fetchedAtMillis: Long = 0L,
        val isLive: Boolean = false,
        val vehicles: Map<String, EspnMapper.Vehicle> = emptyMap(),
        val statistics: Map<String, Map<String, String>> = emptyMap(),
        val statuses: Map<String, EspnMapper.CompetitorStatus> = emptyMap(),
        val lastCompleted: EspnMapper.Competition? = null,
        val seasonFetchedAtMillis: Long = 0L,
        /** Scheduled race distance, keyed by circuit id: it never changes, so it is read once. */
        val lapsByCircuit: Map<String, Int> = emptyMap(),
    )

    private suspend fun tick(cached: Cache): Cache = coroutineScope {
        val now = nowMillis()
        val seasonDue = cached.lastCompleted == null ||
            now - cached.seasonFetchedAtMillis >= seasonIntervalMs
        // First tick only (nothing resolved yet): request the season scoreboard alongside the live
        // one instead of after it. Usually nothing is running and it is needed anyway; when
        // something is, the request is cancelled. Later ticks never speculate, so a live session
        // does not pay for a 600 KB document it will not read.
        val speculativeSeason = if (seasonDue && cached.competitionId == null) {
            async { runCatching { getJson(seasonScoreboardUrl(now)) } }
        } else {
            null
        }

        // A competition flips to "in" on the scoreboard minutes before ESPN lists its competitors,
        // and it keeps that empty roster for a while. Treating that as "the live session" produced
        // a driver-less snapshot every 8 s, which is exactly the flicker between a classification
        // and "No live session" that the fallback used to show. No competitors, no live session.
        val live = EspnMapper.pickLive(EspnMapper.parseScoreboard(getJson(SCOREBOARD_URL)))
            ?.takeIf { it.athletes.isNotEmpty() }

        // Nothing is running: fall back to the last completed session of the season, re-resolved
        // at most once a minute (the season scoreboard barely changes).
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
            // Not even a finished session: the UI shows the "next session" line instead. Anything
            // already published stays - a transient scoreboard hiccup must not blank the screen.
            publish(LiveSessionState.EMPTY.copy(isConnected = true, source = LiveSource.ESPN))
            return@coroutineScope cached.copy(isLive = false, seasonFetchedAtMillis = seasonFetchedAt)
        }

        // One extra request per poll: the flag and the current lap live only here, and both have to
        // keep up with the session rather than with the 30 s statistics refresh. Independent of the
        // driver documents, so it runs alongside them rather than after.
        val competitionStatusJob = async {
            runCatching {
                EspnMapper.parseCompetitionStatus(getJson(competitionStatusUrl(competition)))
            }.getOrNull()
        }

        // The lap count is a property of the circuit, not of the session: fetched once per event.
        val lapsJob = async { cached.lapsByCircuit.withCircuitLaps(competition) }

        // A finished session's documents are loaded once; only a live one keeps refreshing them.
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

    /** Returns the cache unchanged unless this circuit's lap count is still missing. */
    private suspend fun Map<String, Int>.withCircuitLaps(
        competition: EspnMapper.Competition,
    ): Map<String, Int> {
        val id = competition.circuitId
        if (id.isBlank() || containsKey(id)) return this
        val laps = runCatching { EspnMapper.parseCircuitLaps(getJson(circuitUrl(id))) }.getOrNull()
            ?: return this
        return this + (id to laps)
    }

    /**
     * Car identity plus one statistics and one status document per driver. ~45 requests, so it runs
     * at most every [detailIntervalMs] and at most [MAX_PARALLEL_REQUESTS] at a time.
     *
     * Everything goes out at once. The per-driver URLs are built from the scoreboard's competitor
     * ids - the same documents the competitors list links to, minus a `lang` query - so they no
     * longer queue behind that list (~1.5 s on a cold connection), and statistics and statuses share
     * one burst instead of running as two back-to-back ones. Only scoreboard drivers are fetched:
     * [EspnMapper.buildState] maps nobody else. A failed driver document leaves that driver's cells
     * empty; a failed competitors document still fails the round, so a classification without team
     * names is never published (and never persisted).
     */
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

        /** site.api.espn.com answers 403; the site.web host is the public one. */
        const val SCOREBOARD_URL =
            "https://site.web.api.espn.com/apis/site/v2/sports/racing/f1/scoreboard"
        const val CORE_BASE = "https://sports.core.api.espn.com/v2/sports/racing/leagues/f1/events"
        const val CIRCUIT_BASE = "https://sports.core.api.espn.com/v2/sports/racing/leagues/f1/circuits"

        const val USER_AGENT = "Mozilla/5.0"

        const val POLL_INTERVAL_MS = 8_000L
        const val DETAIL_INTERVAL_MS = 30_000L
        const val SEASON_INTERVAL_MS = 60_000L
        /**
         * ESPN's core API speaks HTTP/1.1 only, so parallelism means connections. 16 clears one
         * statistics + status burst for a 22-car field in three rounds (~0.25 s each once warm)
         * instead of nine at OkHttp's default of 5 per host; F1App raises that cap to match.
         */
        const val MAX_PARALLEL_REQUESTS = 16

        /** First retry after a failed poll; doubles up to the regular interval. */
        const val FIRST_RETRY_MS = 2_000L

        /** `?dates=YYYY` lists every event of the season with its per-session state. */
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
