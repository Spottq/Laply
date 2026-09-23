package com.flexy.f1live.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

// ------------------------------------------------------------------ models

@Serializable
data class DriverStanding(
    val position: Int,
    val points: Double,
    val wins: Int,
    val code: String,
    val number: String?,
    val firstName: String,
    val lastName: String,
    val nationality: String,
    val constructorName: String,
    val constructorId: String,
) {
    /** "L. Norris" — the short form used across the app. */
    val shortName: String
        get() = if (firstName.isNotEmpty()) "${firstName.first()}. $lastName" else lastName
}

@Serializable
data class ConstructorStanding(
    val position: Int,
    val points: Double,
    val wins: Int,
    val name: String,
    val constructorId: String,
    val nationality: String,
)

@Serializable
data class Standings(
    val season: Int,
    val round: Int,
    val drivers: List<DriverStanding>,
    val constructors: List<ConstructorStanding>,
)

/**
 * Championship standings from the Jolpica Ergast-compatible API.
 *
 * `GET https://api.jolpi.ca/ergast/f1/{season}/driverStandings.json`
 * `GET https://api.jolpi.ca/ergast/f1/{season}/constructorStandings.json`
 *
 * No auth; the API is rate limited (4 req/s, 500 req/h) so a fetched season stays in memory for
 * the process lifetime and is only re-fetched when `refresh = true` (pull-to-refresh / retry).
 *
 * The screen reads it in two steps: [cached] answers from memory or disk without touching the
 * network - that is what paints the table on a cold, offline start - and [fetch] then refreshes it.
 * When [fetch] fails the cached table stays on screen with an "offline" caption instead of being
 * replaced by an error.
 *
 * Deliberately self-contained: it owns its OkHttp client so it does not depend on the app graph.
 */
object JolpicaStandingsRepository {

    /** Set once in F1App; null in unit tests, which then simply have no disk layer. */
    @Volatile
    var disk: JsonStore? = null

    private const val BASE_URL = "https://api.jolpi.ca/ergast/f1"
    private const val USER_AGENT = "Laply/1.0 (Android; +https://github.com/Spottq/Laply)"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val mutex = Mutex()
    private val cache = mutableMapOf<Int, Standings>()

    /** Memory, then disk. No network, no failure: null just means "nothing stored yet". */
    suspend fun cached(season: Int): Standings? {
        mutex.withLock { cache[season] }?.let { return it }
        val stored = disk?.read(diskKey(season), Standings.serializer()) ?: return null
        if (stored.drivers.isEmpty() && stored.constructors.isEmpty()) return null
        mutex.withLock { cache[season] = stored }
        return stored
    }

    /** Network only; on success both caches are updated. */
    suspend fun fetch(season: Int): Result<Standings> = getStandings(season, refresh = true)

    suspend fun getStandings(season: Int, refresh: Boolean = false): Result<Standings> {
        if (!refresh) {
            cached(season)?.let { return Result.success(it) }
        }
        return runCatching {
            val standings = withContext(Dispatchers.IO) {
                coroutineScope {
                    // Both requests are in flight before the first await.
                    val driversAsync = async { fetchDrivers(season) }
                    val constructorsAsync = async { fetchConstructors(season) }
                    val drivers = driversAsync.await()
                    val constructors = constructorsAsync.await()
                    Standings(
                        season = drivers.season ?: constructors.season ?: season,
                        round = drivers.round ?: constructors.round ?: 0,
                        drivers = drivers.items,
                        constructors = constructors.items,
                    )
                }
            }
            mutex.withLock { cache[season] = standings }
            disk?.write(diskKey(season), Standings.serializer(), standings)
            standings
        }
    }

    fun diskKey(season: Int): String = "standings_" + season

    // ------------------------------------------------------------------ fetching

    private class DriverList(val season: Int?, val round: Int?, val items: List<DriverStanding>)

    private class ConstructorList(val season: Int?, val round: Int?, val items: List<ConstructorStanding>)

    private fun fetchDrivers(season: Int): DriverList {
        val list = get("$BASE_URL/$season/driverStandings.json?limit=100")
            .data.standingsTable.lists.firstOrNull()
        val items = list?.driverStandings.orEmpty().mapIndexedNotNull { index, dto ->
            val driver = dto.driver ?: return@mapIndexedNotNull null
            val constructor = dto.constructors.lastOrNull()
            DriverStanding(
                position = dto.position?.toIntOrNull() ?: (index + 1),
                points = dto.points?.toDoubleOrNull() ?: 0.0,
                wins = dto.wins?.toIntOrNull() ?: 0,
                code = driver.code ?: driver.familyName?.take(3)?.uppercase().orEmpty(),
                number = driver.permanentNumber,
                firstName = driver.givenName.orEmpty(),
                lastName = driver.familyName.orEmpty(),
                nationality = driver.nationality.orEmpty(),
                constructorName = constructor?.name.orEmpty(),
                constructorId = constructor?.constructorId.orEmpty(),
            )
        }
        return DriverList(list?.season?.toIntOrNull(), list?.round?.toIntOrNull(), items)
    }

    private fun fetchConstructors(season: Int): ConstructorList {
        val list = get("$BASE_URL/$season/constructorStandings.json?limit=100")
            .data.standingsTable.lists.firstOrNull()
        val items = list?.constructorStandings.orEmpty().mapIndexedNotNull { index, dto ->
            val constructor = dto.constructor ?: return@mapIndexedNotNull null
            ConstructorStanding(
                position = dto.position?.toIntOrNull() ?: (index + 1),
                points = dto.points?.toDoubleOrNull() ?: 0.0,
                wins = dto.wins?.toIntOrNull() ?: 0,
                name = constructor.name.orEmpty(),
                constructorId = constructor.constructorId.orEmpty(),
                nationality = constructor.nationality.orEmpty(),
            )
        }
        return ConstructorList(list?.season?.toIntOrNull(), list?.round?.toIntOrNull(), items)
    }

    private fun get(url: String): ErgastStandingsResponse {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Jolpica request failed: HTTP ${response.code}")
            }
            return json.decodeFromString<ErgastStandingsResponse>(response.body.string())
        }
    }

    // ------------------------------------------------------------------- dtos

    @Serializable
    private class ErgastStandingsResponse(@SerialName("MRData") val data: MrData)

    @Serializable
    private class MrData(
        @SerialName("StandingsTable") val standingsTable: StandingsTable = StandingsTable(),
    )

    @Serializable
    private class StandingsTable(
        @SerialName("StandingsLists") val lists: List<StandingsListDto> = emptyList(),
    )

    @Serializable
    private class StandingsListDto(
        val season: String? = null,
        val round: String? = null,
        @SerialName("DriverStandings") val driverStandings: List<DriverStandingDto>? = null,
        @SerialName("ConstructorStandings") val constructorStandings: List<ConstructorStandingDto>? = null,
    )

    @Serializable
    private class DriverStandingDto(
        val position: String? = null,
        val positionText: String? = null,
        val points: String? = null,
        val wins: String? = null,
        @SerialName("Driver") val driver: DriverDto? = null,
        @SerialName("Constructors") val constructors: List<ConstructorDto> = emptyList(),
    )

    @Serializable
    private class ConstructorStandingDto(
        val position: String? = null,
        val positionText: String? = null,
        val points: String? = null,
        val wins: String? = null,
        @SerialName("Constructor") val constructor: ConstructorDto? = null,
    )

    @Serializable
    private class DriverDto(
        val driverId: String? = null,
        val permanentNumber: String? = null,
        val code: String? = null,
        val givenName: String? = null,
        val familyName: String? = null,
        val nationality: String? = null,
        val url: String? = null,
    )

    @Serializable
    private class ConstructorDto(
        val constructorId: String? = null,
        val name: String? = null,
        val nationality: String? = null,
    )
}

// ------------------------------------------------------------- team colours

/** Neutral grey used when a constructor is not in the table (new or historical entrants). */
private const val FALLBACK_TEAM_HEX = "8A8A8A"

/** Ergast `constructorId` -> the team's livery colour, as a 6-digit hex string (no leading `#`). */
private val CONSTRUCTOR_COLORS: Map<String, String> = mapOf(
    "mclaren" to "F47600",
    "red_bull" to "3671C6",
    "ferrari" to "E8002D",
    "mercedes" to "27F4D2",
    "aston_martin" to "229971",
    "alpine" to "0093CC",
    "williams" to "64C4FF",
    "rb" to "6692FF",
    "racing_bulls" to "6692FF",
    "alphatauri" to "6692FF",
    "toro_rosso" to "6692FF",
    "haas" to "B6BABD",
    "sauber" to "52E252",
    "audi" to "52E252",
    "alfa" to "52E252",
    "cadillac" to "C9A227",
    "renault" to "0093CC",
    "force_india" to "F596C8",
    "racing_point" to "F596C8",
)

/** Team livery colour for an Ergast constructor id; a neutral grey when unknown. */
fun constructorColorHex(constructorId: String?): String {
    val key = constructorId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return FALLBACK_TEAM_HEX
    return CONSTRUCTOR_COLORS[key] ?: FALLBACK_TEAM_HEX
}

// ----------------------------------------------------------- nationalities

/** Ergast demonyms ("British") -> ISO 3166-1 alpha-2, lowercase. */
private val DEMONYM_TO_ALPHA2: Map<String, String> = mapOf(
    "british" to "gb", "english" to "gb", "scottish" to "gb", "welsh" to "gb",
    "dutch" to "nl", "italian" to "it", "french" to "fr", "monegasque" to "mc",
    "argentine" to "ar", "argentinian" to "ar", "australian" to "au", "spanish" to "es",
    "german" to "de", "canadian" to "ca", "mexican" to "mx", "japanese" to "jp",
    "thai" to "th", "brazilian" to "br", "finnish" to "fi", "new zealander" to "nz",
    "american" to "us", "swedish" to "se", "danish" to "dk", "chinese" to "cn",
    "swiss" to "ch", "austrian" to "at", "belgian" to "be", "irish" to "ie",
    "polish" to "pl", "russian" to "ru", "portuguese" to "pt", "hungarian" to "hu",
    "indian" to "in", "indonesian" to "id", "malaysian" to "my", "venezuelan" to "ve",
    "colombian" to "co", "czech" to "cz", "south african" to "za", "chilean" to "cl",
    "uruguayan" to "uy", "israeli" to "il",
)

/**
 * Country code for a driver's flag. Prefers the curated TLA table (which knows the current grid)
 * and falls back to the Ergast demonym; null when neither resolves.
 */
fun driverCountryCode(tla: String?, nationality: String?): String? {
    DriverNationality.forTla(tla)?.let { return it }
    val key = nationality?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return DEMONYM_TO_ALPHA2[key]
}
