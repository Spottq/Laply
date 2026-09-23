package com.flexy.f1live.data

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Season schedule from the Jolpica Ergast-compatible API.
 *
 * `GET https://api.jolpi.ca/ergast/f1/{season}.json?limit=100`
 *
 * The API is rate limited (4 req/s, 500 req/h) and a season calendar barely changes, so the result
 * is cached in memory for the process lifetime and only re-fetched when `refresh = true`.
 *
 * It is also mirrored to `filesDir/cache-json/schedule_{season}.json`, which is what the app reads
 * when it starts offline: the calendar is what every other screen keys off (which round a finished
 * session belongs to, which weekend a track map is for), so losing it to a failed request would
 * take the schedule, the results cache and the map cache down with it.
 */
class JolpicaScheduleRepository(
    private val http: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val disk: JsonStore? = null,
    /**
     * Called after a successful network fetch (never for memory or disk hits), so whoever derives
     * timers from the calendar - the auto-follow alarm - can re-arm them when it actually changed.
     */
    private val onFetched: ((season: Int, weekends: List<RaceWeekend>) -> Unit)? = null,
) : ScheduleRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val mutex = Mutex()
    private val cache = mutableMapOf<Int, List<RaceWeekend>>()

    override suspend fun getSeason(season: Int, refresh: Boolean): Result<List<RaceWeekend>> {
        if (!refresh) {
            mutex.withLock { cache[season] }?.let { return Result.success(it) }
            readDisk(season)?.let { stored ->
                mutex.withLock { cache[season] = stored }
                // Still refresh in the background? No: a calendar that is already on disk is good
                // enough for this launch, and `refresh = true` (pull to refresh) covers the rest.
                return Result.success(stored)
            }
        }
        return runCatching {
            val weekends = withContext(Dispatchers.IO) { fetchSeason(season) }
            mutex.withLock { cache[season] = weekends }
            writeDisk(season, weekends)
            if (weekends.isNotEmpty()) onFetched?.invoke(season, weekends)
            weekends
        }.recoverCatching { error ->
            // Offline with nothing in memory: the stored calendar beats an error screen.
            readDisk(season)?.also { stored -> mutex.withLock { cache[season] = stored } }
                ?: throw error
        }
    }

    private suspend fun readDisk(season: Int): List<RaceWeekend>? =
        disk?.read(diskKey(season), WeekendListSerializer)?.takeIf { it.isNotEmpty() }

    private suspend fun writeDisk(season: Int, weekends: List<RaceWeekend>) {
        if (weekends.isEmpty()) return
        disk?.write(diskKey(season), WeekendListSerializer, weekends)
    }

    private fun fetchSeason(season: Int): List<RaceWeekend> {
        val request = Request.Builder()
            .url("$baseUrl/$season.json?limit=100")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Jolpica request failed: HTTP ${response.code}")
            }
            val body = response.body.string()
            val payload = json.decodeFromString<ErgastResponse>(body)
            return payload.data.raceTable.races.map { it.toRaceWeekend(season) }
        }
    }

    // ------------------------------------------------------------------ mapping

    private fun RaceDto.toRaceWeekend(fallbackSeason: Int): RaceWeekend {
        val location = circuit?.location
        val slots = listOf(
            SessionKind.PRACTICE1 to firstPractice,
            SessionKind.PRACTICE2 to secondPractice,
            SessionKind.PRACTICE3 to thirdPractice,
            SessionKind.SPRINT_QUALIFYING to (sprintQualifying ?: sprintShootout),
            SessionKind.SPRINT to sprint,
            SessionKind.QUALIFYING to qualifying,
            SessionKind.RACE to date?.let { DateTimeDto(it, time) },
        )
        val sessions = slots
            .mapNotNull { (kind, slot) ->
                if (slot == null) null
                else ScheduledSession(kind, kind.displayName(), toUtcMillis(slot.date, slot.time))
            }
            .sortedWith(compareBy(nullsLast()) { it.startUtcMillis })

        val country = location?.country.orEmpty()
        return RaceWeekend(
            season = season?.toIntOrNull() ?: fallbackSeason,
            round = round?.toIntOrNull() ?: 0,
            name = raceName.orEmpty(),
            country = country,
            locality = location?.locality.orEmpty(),
            circuitName = circuit?.circuitName.orEmpty(),
            countryCode = countryCodeOf(country),
            sessions = sessions,
            circuitWikiUrl = circuit?.url?.takeIf { it.isNotBlank() },
        )
    }

    private fun SessionKind.displayName(): String = when (this) {
        SessionKind.PRACTICE1 -> "Practice 1"
        SessionKind.PRACTICE2 -> "Practice 2"
        SessionKind.PRACTICE3 -> "Practice 3"
        SessionKind.SPRINT_QUALIFYING -> "Sprint Qualifying"
        SessionKind.SPRINT -> "Sprint"
        SessionKind.QUALIFYING -> "Qualifying"
        SessionKind.RACE -> "Race"
        SessionKind.UNKNOWN -> "Session"
    }

    /** Ergast splits the instant into `"2026-09-07"` + `"13:00:00Z"`; the time may be missing. */
    private fun toUtcMillis(date: String?, time: String?): Long? {
        val day = date?.trim().orEmpty()
        if (day.isEmpty()) return null
        val clock = time?.trim().orEmpty()
        return try {
            if (clock.isEmpty()) {
                LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } else if (clock.endsWith("Z", ignoreCase = true)) {
                Instant.parse("${day}T$clock").toEpochMilli()
            } else {
                LocalDateTime.parse("${day}T$clock").toInstant(ZoneOffset.UTC).toEpochMilli()
            }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    // ------------------------------------------------------------------- dtos

    @Serializable
    private class ErgastResponse(@SerialName("MRData") val data: MrData)

    @Serializable
    private class MrData(@SerialName("RaceTable") val raceTable: RaceTable = RaceTable())

    @Serializable
    private class RaceTable(@SerialName("Races") val races: List<RaceDto> = emptyList())

    @Serializable
    private class RaceDto(
        val season: String? = null,
        val round: String? = null,
        val raceName: String? = null,
        val date: String? = null,
        val time: String? = null,
        @SerialName("Circuit") val circuit: CircuitDto? = null,
        @SerialName("FirstPractice") val firstPractice: DateTimeDto? = null,
        @SerialName("SecondPractice") val secondPractice: DateTimeDto? = null,
        @SerialName("ThirdPractice") val thirdPractice: DateTimeDto? = null,
        @SerialName("Qualifying") val qualifying: DateTimeDto? = null,
        @SerialName("Sprint") val sprint: DateTimeDto? = null,
        @SerialName("SprintQualifying") val sprintQualifying: DateTimeDto? = null,
        @SerialName("SprintShootout") val sprintShootout: DateTimeDto? = null,
    )

    @Serializable
    private class CircuitDto(
        val circuitName: String? = null,
        /** Wikipedia page of the circuit; the track-map fallback when F1's CDN has no map. */
        val url: String? = null,
        @SerialName("Location") val location: LocationDto? = null,
    )

    @Serializable
    private class LocationDto(val locality: String? = null, val country: String? = null)

    @Serializable
    private class DateTimeDto(val date: String? = null, val time: String? = null)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.jolpi.ca/ergast/f1"

        private val WeekendListSerializer = ListSerializer(RaceWeekend.serializer())

        fun diskKey(season: Int): String = "schedule_" + season

        private const val USER_AGENT = "Laply/1.0 (Android; +https://github.com/Spottq/Laply)"

        /** Ergast country names -> ISO 3166-1 alpha-2, lowercase. */
        private val COUNTRY_TO_ALPHA2: Map<String, String> = mapOf(
            "argentina" to "ar", "australia" to "au", "austria" to "at", "azerbaijan" to "az",
            "bahrain" to "bh", "belgium" to "be", "brazil" to "br", "canada" to "ca",
            "china" to "cn", "croatia" to "hr", "czech republic" to "cz", "czechia" to "cz",
            "denmark" to "dk", "finland" to "fi", "france" to "fr", "germany" to "de",
            "hungary" to "hu", "india" to "in", "indonesia" to "id", "ireland" to "ie",
            "italy" to "it", "japan" to "jp", "korea" to "kr", "south korea" to "kr",
            "malaysia" to "my", "mexico" to "mx", "monaco" to "mc", "morocco" to "ma",
            "netherlands" to "nl", "new zealand" to "nz", "poland" to "pl", "portugal" to "pt",
            "qatar" to "qa", "russia" to "ru", "saudi arabia" to "sa", "singapore" to "sg",
            "south africa" to "za", "spain" to "es", "sweden" to "se", "switzerland" to "ch",
            "thailand" to "th", "turkey" to "tr", "uae" to "ae", "united arab emirates" to "ae",
            "uk" to "gb", "united kingdom" to "gb", "great britain" to "gb", "england" to "gb",
            "usa" to "us", "united states" to "us", "united states of america" to "us",
            "vietnam" to "vn",
        )

        /** Circuit locations Ergast labels with something other than a plain country name. */
        private val LOCALITY_ALIASES: Map<String, String> = mapOf(
            "korea" to "kr", "europe" to "az", "san marino" to "sm",
        )

        /** Country name -> ISO 3166-1 alpha-2, lowercase; null when unknown. */
        fun countryCodeOf(country: String?): String? {
            val key = country?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return COUNTRY_TO_ALPHA2[key] ?: LOCALITY_ALIASES[key]
        }
    }
}
