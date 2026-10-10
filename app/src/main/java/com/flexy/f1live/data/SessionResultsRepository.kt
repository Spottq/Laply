package com.flexy.f1live.data

import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.LiveSource
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SectorTiming
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.model.TrackFlag
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.math.abs

class SessionResultsRepository(
    private val http: OkHttpClient,
    private val store: SessionResultsStore,
    private val jolpicaBaseUrl: String = JolpicaScheduleRepository.DEFAULT_BASE_URL,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onResults: (LiveSessionState) -> Unit = {},
) {

    suspend fun getResults(
        weekend: RaceWeekend,
        session: ScheduledSession,
    ): Result<SessionResults> {
        store.read(weekend.season, weekend.round, session.kind)?.let { cached ->
            if (cached.drivers.isNotEmpty()) {
                onResults(cached)
                return Result.success(SessionResults(cached, fastestLapNumberOf(cached)))
            }
        }
        return runCatching {
            val results = fetch(weekend, session)
                ?: throw IOException("No results published for " + weekend.name + " " + session.name)
            store.write(weekend.season, weekend.round, session.kind, results.state)
            onResults(results.state)
            results
        }
    }

    private suspend fun fetch(weekend: RaceWeekend, session: ScheduledSession): SessionResults? {
        val document = when (session.kind) {
            SessionKind.RACE -> "results"
            SessionKind.SPRINT -> "sprint"
            SessionKind.QUALIFYING -> "qualifying"
            else -> null
        }
        if (document != null) {
            jolpica(weekend, session, document)?.let { if (it.state.drivers.isNotEmpty()) return it }
        }
        return espn(weekend, session)?.let { SessionResults(it, null) }
    }

    // ------------------------------------------------------------------- jolpica

    private suspend fun jolpica(
        weekend: RaceWeekend,
        session: ScheduledSession,
        document: String,
    ): SessionResults? = runCatching {
        val url = jolpicaBaseUrl + "/" + weekend.season + "/" + weekend.round + "/" +
            document + ".json?limit=40"
        val body = http.getText(url, JOLPICA_USER_AGENT)
        val state = JolpicaResultsMapper.parse(body, session, nowMillis()) ?: return@runCatching null
        SessionResults(state, JolpicaResultsMapper.fastestLapRacingNumber(body))
    }.getOrNull()

    // ---------------------------------------------------------------------- espn

    private suspend fun espn(
        weekend: RaceWeekend,
        session: ScheduledSession,
    ): LiveSessionState? = coroutineScope {
        val scoreboard = EspnMapper.parseScoreboard(
            http.getJsonObject(seasonScoreboardUrl(weekend.season), ESPN_USER_AGENT),
        )
        val competition = pickCompetition(scoreboard, weekend, session) ?: return@coroutineScope null
        if (competition.athletes.isEmpty()) return@coroutineScope null

        val vehiclesJob = async {
            runCatching {
                EspnMapper.parseCompetitors(
                    http.getJsonObject(
                        competitorsUrl(competition.eventId, competition.competitionId),
                        ESPN_USER_AGENT,
                    ),
                )
            }.getOrDefault(emptyList()).associateBy { it.competitorId }
        }

        val ids = competition.athletes.map { it.competitorId }.distinct()
        val gate = Semaphore(MAX_PARALLEL_REQUESTS)
        val statisticsJobs = ids.map { id ->
            async {
                id to gate.withPermit {
                    runCatching {
                        EspnMapper.parseStatistics(
                            http.getJsonObject(competitorUrl(competition, id, "statistics"), ESPN_USER_AGENT),
                        )
                    }.getOrNull()
                }
            }
        }
        val vehicles = vehiclesJob.await()
        val statistics = statisticsJobs.awaitAll()
            .mapNotNull { (id, stats) -> stats?.let { id to it } }.toMap()

        EspnMapper.buildState(
            competition = competition,
            vehicles = vehicles,
            statistics = statistics,
            statuses = emptyMap(),
            nowUtcMillis = nowMillis(),
        ).copy(
            isConnected = false,
            status = SessionStatus.FINISHED,
            sessionName = session.name.ifBlank { competition.sessionName },
            sessionKind = session.kind,
            meetingName = weekend.name.ifBlank { competition.meetingName },
            meetingCountry = weekend.country.ifBlank { competition.country },
            meetingLocation = weekend.locality.ifBlank { competition.city },
        )
    }

    private fun pickCompetition(
        scoreboard: List<EspnMapper.Competition>,
        weekend: RaceWeekend,
        session: ScheduledSession,
    ): EspnMapper.Competition? {
        val event = scoreboard.groupBy { it.eventId }.values
            .firstOrNull { competitions -> matchesWeekend(competitions, weekend) }
            ?: return null
        return event.firstOrNull { it.sessionKind == session.kind }
            ?: event.firstOrNull { sameStartDay(it.startUtcMillis, session.startUtcMillis) }
    }

    private fun matchesWeekend(
        competitions: List<EspnMapper.Competition>,
        weekend: RaceWeekend,
    ): Boolean {
        val race = competitions.firstOrNull { it.sessionKind == SessionKind.RACE }?.startUtcMillis
        val target = weekend.raceStartUtcMillis
        if (race != null && target != null && abs(race - target) <= EVENT_MATCH_WINDOW_MS) return true
        val name = competitions.firstOrNull()?.meetingName.orEmpty()
        return name.isNotBlank() && name.equals(weekend.name.trim(), ignoreCase = true)
    }

    private fun sameStartDay(a: Long?, b: Long?): Boolean =
        a != null && b != null && abs(a - b) <= DAY_MS

    private fun fastestLapNumberOf(state: LiveSessionState): String? {
        if (state.sessionKind != SessionKind.RACE && state.sessionKind != SessionKind.SPRINT) return null
        return state.drivers
            .filter { it.bestLapTime.isNotBlank() }
            .minByOrNull { lapMillis(it.bestLapTime) }
            ?.racingNumber
    }

    private companion object {
        const val JOLPICA_USER_AGENT = "Laply/1.0 (Android; +https://github.com/Spottq/Laply)"
        const val ESPN_USER_AGENT = "Mozilla/5.0"
        const val MAX_PARALLEL_REQUESTS = 16
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val EVENT_MATCH_WINDOW_MS = 2 * DAY_MS

        const val SCOREBOARD_URL =
            "https://site.web.api.espn.com/apis/site/v2/sports/racing/f1/scoreboard"
        const val CORE_BASE = "https://sports.core.api.espn.com/v2/sports/racing/leagues/f1/events"

        fun seasonScoreboardUrl(season: Int) = SCOREBOARD_URL + "?dates=" + season

        fun competitorsUrl(eventId: String, competitionId: String) =
            CORE_BASE + "/" + eventId + "/competitions/" + competitionId + "/competitors?limit=30"

        fun competitorUrl(
            competition: EspnMapper.Competition,
            competitorId: String,
            document: String,
        ) = CORE_BASE + "/" + competition.eventId + "/competitions/" +
            competition.competitionId + "/competitors/" + competitorId + "/" + document
    }
}

data class SessionResults(
    val state: LiveSessionState,
    val fastestLapRacingNumber: String?,
)

internal fun lapMillis(value: String): Long {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return Long.MAX_VALUE
    val parts = trimmed.split(':')
    val seconds = parts.last().toDoubleOrNull() ?: return Long.MAX_VALUE
    val minutes = if (parts.size > 1) parts[parts.size - 2].toLongOrNull() ?: 0L else 0L
    val hours = if (parts.size > 2) parts[parts.size - 3].toLongOrNull() ?: 0L else 0L
    return ((hours * 3600 + minutes * 60) * 1000) + (seconds * 1000).toLong()
}

object JolpicaResultsMapper {

    fun parse(body: String, session: ScheduledSession, nowUtcMillis: Long): LiveSessionState? {
        val race = LenientJson.decodeFromString<ErgastResultsResponse>(body)
            .data.raceTable.races.firstOrNull() ?: return null
        val finishers = race.results.ifEmpty { race.sprintResults }
        val raceLaps = finishers.mapNotNull { it.laps?.toIntOrNull() }.maxOrNull() ?: 0
        val classification = when {
            race.qualifyingResults.isNotEmpty() -> race.qualifyingResults.map(::qualifyingTiming)
            finishers.isNotEmpty() -> finishers.map { raceTiming(it, raceLaps) }
            else -> return null
        }.sortedBy { if (it.position > 0) it.position else Int.MAX_VALUE }

        val isQualifying = race.qualifyingResults.isNotEmpty()
        return LiveSessionState(
            isConnected = false,
            meetingName = race.raceName.orEmpty(),
            meetingCountry = race.circuit?.location?.country.orEmpty(),
            meetingLocation = race.circuit?.location?.locality.orEmpty(),
            circuitShortName = race.circuit?.location?.locality.orEmpty(),
            sessionName = session.name,
            sessionKind = session.kind,
            sessionPart = if (isQualifying) 3 else null,
            status = SessionStatus.FINISHED,
            trackFlag = TrackFlag.UNKNOWN,
            airTempC = null,
            trackTempC = null,
            humidityPct = null,
            rainfall = null,
            currentLap = null,
            totalLaps = classification.maxOfOrNull { it.numberOfLaps }?.takeIf { it > 0 },
            drivers = classification,
            raceControl = emptyList(),
            lastUpdateUtcMillis = nowUtcMillis,
            source = LiveSource.JOLPICA,
        )
    }

    fun fastestLapRacingNumber(body: String): String? {
        val race = runCatching {
            LenientJson.decodeFromString<ErgastResultsResponse>(body).data.raceTable.races.firstOrNull()
        }.getOrNull() ?: return null
        val results = race.results.ifEmpty { race.sprintResults }
        val fastest = results.firstOrNull { it.fastestLap?.rank == "1" } ?: return null
        return racingNumberOf(fastest.number, fastest.driver)
    }

    fun isClassifiedStatus(status: String): Boolean {
        val value = status.trim()
        return value.startsWith("Finished", ignoreCase = true) ||
            value.startsWith("Lapped", ignoreCase = true) ||
            value.startsWith("Classified", ignoreCase = true) ||
            value.startsWith("+")
    }

    // ------------------------------------------------------------------ mapping

    private fun raceTiming(dto: ResultDto, raceLaps: Int): DriverTiming {
        val position = dto.position?.toIntOrNull() ?: 0
        val status = dto.status.orEmpty()
        val total = dto.time?.time.orEmpty()
        val laps = dto.laps?.toIntOrNull() ?: 0
        val classified = isClassifiedStatus(status)
        val lapsDown = if (raceLaps > 0 && laps in 1 until raceLaps) raceLaps - laps else 0
        return baseTiming(dto).copy(
            position = position,
            lastLapTime = if (position == 1) total else "",
            gapToLeader = when {
                position == 1 -> ""
                lapsDown > 0 && classified -> "+" + lapsDown + if (lapsDown == 1) " Lap" else " Laps"
                else -> total.ifBlank { status }
            },
            bestLapTime = dto.fastestLap?.time?.time.orEmpty(),
            numberOfLaps = laps,
            retired = !classified,
            sectors = emptyList(),
        )
    }

    private fun qualifyingTiming(dto: QualifyingResultDto): DriverTiming {
        val tla = tlaOf(dto.driver)
        val position = dto.position?.toIntOrNull() ?: 0
        val parts = listOf(dto.q1.orEmpty(), dto.q2.orEmpty(), dto.q3.orEmpty())
        return DriverTiming(
            position = position,
            racingNumber = racingNumberOf(dto.number, dto.driver),
            tla = tla,
            firstName = dto.driver?.givenName.orEmpty(),
            lastName = dto.driver?.familyName.orEmpty(),
            shortName = shortNameOf(dto.driver),
            teamName = dto.constructor?.name.orEmpty(),
            teamColorHex = constructorColorHex(dto.constructor?.constructorId),
            headshotUrl = DriverHeadshots.forTla(tla),
            countryCode = driverCountryCode(tla, dto.driver?.nationality),
            bestLapTime = parts.lastOrNull { it.isNotBlank() }.orEmpty(),
            lastLapTime = "",
            gapToLeader = "",
            interval = "",
            sectors = parts.map { SectorTiming(it, personalFastest = false, overallFastest = false) },
            inPit = false,
            pitOut = false,
            retired = false,
            stopped = false,
            knockedOut = position > 10,
            numberOfLaps = 0,
            numberOfPitStops = 0,
            tyreCompound = null,
        )
    }

    private fun baseTiming(dto: ResultDto): DriverTiming {
        val tla = tlaOf(dto.driver)
        return DriverTiming(
            position = 0,
            racingNumber = racingNumberOf(dto.number, dto.driver),
            tla = tla,
            firstName = dto.driver?.givenName.orEmpty(),
            lastName = dto.driver?.familyName.orEmpty(),
            shortName = shortNameOf(dto.driver),
            teamName = dto.constructor?.name.orEmpty(),
            teamColorHex = constructorColorHex(dto.constructor?.constructorId),
            headshotUrl = DriverHeadshots.forTla(tla),
            countryCode = driverCountryCode(tla, dto.driver?.nationality),
            bestLapTime = "",
            lastLapTime = "",
            gapToLeader = "",
            interval = "",
            sectors = emptyList(),
            inPit = false,
            pitOut = false,
            retired = false,
            stopped = false,
            knockedOut = false,
            numberOfLaps = 0,
            numberOfPitStops = 0,
            tyreCompound = null,
        )
    }

    private fun tlaOf(driver: DriverDto?): String =
        driver?.code?.takeIf { it.isNotBlank() }
            ?: driver?.familyName?.take(3)?.uppercase().orEmpty()

    private fun racingNumberOf(number: String?, driver: DriverDto?): String =
        number?.takeIf { it.isNotBlank() }
            ?: driver?.permanentNumber?.takeIf { it.isNotBlank() }
            ?: driver?.driverId.orEmpty()

    private fun shortNameOf(driver: DriverDto?): String =
        DriverNames.short(driver?.givenName.orEmpty(), driver?.familyName.orEmpty())

    // ------------------------------------------------------------------- dtos

    @Serializable
    class ErgastResultsResponse(@SerialName("MRData") val data: MrData = MrData())

    @Serializable
    class MrData(@SerialName("RaceTable") val raceTable: RaceTable = RaceTable())

    @Serializable
    class RaceTable(@SerialName("Races") val races: List<RaceResultsDto> = emptyList())

    @Serializable
    class RaceResultsDto(
        val season: String? = null,
        val round: String? = null,
        val raceName: String? = null,
        val date: String? = null,
        val time: String? = null,
        @SerialName("Circuit") val circuit: CircuitDto? = null,
        @SerialName("Results") val results: List<ResultDto> = emptyList(),
        @SerialName("SprintResults") val sprintResults: List<ResultDto> = emptyList(),
        @SerialName("QualifyingResults") val qualifyingResults: List<QualifyingResultDto> = emptyList(),
    )

    @Serializable
    class CircuitDto(
        val circuitId: String? = null,
        val url: String? = null,
        val circuitName: String? = null,
        @SerialName("Location") val location: LocationDto? = null,
    )

    @Serializable
    class LocationDto(val locality: String? = null, val country: String? = null)

    @Serializable
    class ResultDto(
        val number: String? = null,
        val position: String? = null,
        val positionText: String? = null,
        val points: String? = null,
        val grid: String? = null,
        val laps: String? = null,
        val status: String? = null,
        @SerialName("Time") val time: TimeDto? = null,
        @SerialName("FastestLap") val fastestLap: FastestLapDto? = null,
        @SerialName("Driver") val driver: DriverDto? = null,
        @SerialName("Constructor") val constructor: ConstructorDto? = null,
    )

    @Serializable
    class QualifyingResultDto(
        val number: String? = null,
        val position: String? = null,
        @SerialName("Q1") val q1: String? = null,
        @SerialName("Q2") val q2: String? = null,
        @SerialName("Q3") val q3: String? = null,
        @SerialName("Driver") val driver: DriverDto? = null,
        @SerialName("Constructor") val constructor: ConstructorDto? = null,
    )

    @Serializable
    class TimeDto(val millis: String? = null, val time: String? = null)

    @Serializable
    class FastestLapDto(
        val rank: String? = null,
        val lap: String? = null,
        @SerialName("Time") val time: TimeDto? = null,
    )

    @Serializable
    class DriverDto(
        val driverId: String? = null,
        val permanentNumber: String? = null,
        val code: String? = null,
        val givenName: String? = null,
        val familyName: String? = null,
        val nationality: String? = null,
    )

    @Serializable
    class ConstructorDto(
        val constructorId: String? = null,
        val name: String? = null,
        val nationality: String? = null,
    )
}
