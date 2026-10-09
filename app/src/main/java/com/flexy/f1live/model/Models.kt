package com.flexy.f1live.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Shared domain contract. Data layer produces these, UI and Live Updates consume them.
 *
 * Every type here is deeply immutable, but several carry `List<>` properties which Compose treats
 * as unstable by default. Under strong skipping an unstable parameter is compared by *instance*
 * identity, so a freshly parsed - yet structurally identical - snapshot would recompose every row.
 * [Immutable] promises deep immutability, which lets Compose compare with `equals` and skip.
 *
 * The live-timing half of the contract is also [Serializable]: finished sessions are written to
 * `filesDir/cache-json/` so the Live tab can paint the last classification before the network
 * answers, and so a past session opened from the schedule costs nothing the second time.
 */

@Serializable
enum class SessionKind { PRACTICE1, PRACTICE2, PRACTICE3, SPRINT_QUALIFYING, SPRINT, QUALIFYING, RACE, UNKNOWN }

@Immutable
@Serializable
data class ScheduledSession(
    val kind: SessionKind,
    val name: String,            // "Practice 1", "Qualifying", "Race"
    val startUtcMillis: Long?,   // null if time unknown
)

@Immutable
@Serializable
data class RaceWeekend(
    val season: Int,
    val round: Int,
    val name: String,            // "Italian Grand Prix"
    val country: String,         // "Italy"
    val locality: String,        // "Monza"
    val circuitName: String,     // "Autodromo Nazionale Monza"
    val countryCode: String?,    // ISO 3166-1 alpha-2, lowercase, e.g. "it"; null if unknown
    val sessions: List<ScheduledSession>,
    /** Circuit's Wikipedia page, e.g. "https://en.wikipedia.org/wiki/Circuit_Zandvoort"; the
     *  fallback source for a track map when F1's media CDN has none. */
    val circuitWikiUrl: String? = null,
    /** Where the circuit is, for the weekend forecast; null in calendars cached before 1.1. */
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val raceStartUtcMillis: Long? get() = sessions.firstOrNull { it.kind == SessionKind.RACE }?.startUtcMillis
}

@Serializable
enum class SessionStatus { INACTIVE, STARTED, ABORTED, FINISHED, FINALISED, ENDS, UNKNOWN }

/** Which feed produced a [LiveSessionState]. */
@Serializable
enum class LiveSource {
    NONE,
    F1_LIVE_TIMING,
    ESPN,

    /** Ergast-compatible results from Jolpica: finished races, sprints and qualifying. */
    JOLPICA,

    /** Replayed from `filesDir/cache-json/`, i.e. shown before (or instead of) any network call. */
    CACHE,
}

@Serializable
enum class TrackFlag { GREEN, YELLOW, SC, RED, VSC, VSC_ENDING, UNKNOWN }

@Immutable
@Serializable
data class SectorTiming(
    val value: String,           // "37.045" or ""
    val personalFastest: Boolean,
    val overallFastest: Boolean,
)

@Immutable
@Serializable
data class DriverTiming(
    val position: Int,           // 1-based; 0 if unknown
    val racingNumber: String,    // "1"
    val tla: String,             // "NOR"
    val firstName: String,
    val lastName: String,
    val shortName: String,       // "L. Norris"
    val teamName: String,        // "McLaren"
    val teamColorHex: String?,   // "F47600" (no #), null if unknown
    val headshotUrl: String?,
    val countryCode: String?,    // ISO alpha-2 lowercase, e.g. "gb"; null if unknown
    val bestLapTime: String,     // "1:22.612" or ""
    val lastLapTime: String,     // "" if none
    val gapToLeader: String,     // "+0.019" / "" / "1 LAP"
    val interval: String,        // gap to car ahead
    val sectors: List<SectorTiming>,
    val inPit: Boolean,
    val pitOut: Boolean,
    val retired: Boolean,
    val stopped: Boolean,
    val knockedOut: Boolean,     // eliminated in Q1/Q2
    val numberOfLaps: Int,
    val numberOfPitStops: Int,
    val tyreCompound: String?,   // "SOFT" / "MEDIUM" / "HARD" / "INTERMEDIATE" / "WET" / null
    /**
     * True for the one driver holding the fastest lap of the session (`TimingStats.Lines[n].
     * PersonalBestLapTime.Position == 1`). Sources that publish no lap times leave it false.
     */
    val fastestLap: Boolean = false,
    /**
     * The driver's best sector times of the session, which is a different thing from [sectors]:
     * the feed's `TimingData.Sectors` are the *last* lap, so a finished session would otherwise
     * show whatever happened on an in-lap. Empty for sources that do not publish them.
     */
    val bestSectors: List<SectorTiming> = emptyList(),
)

@Immutable
@Serializable
data class RaceControlMessage(
    val utcMillis: Long?,
    val category: String,        // "Flag", "Other", "Drs", "SafetyCar"
    val message: String,
    val flag: String?,           // "YELLOW", "GREEN", "CHEQUERED", ...
)

@Immutable
@Serializable
data class LiveSessionState(
    val isConnected: Boolean,
    val meetingName: String,     // "Italian Grand Prix"
    val meetingCountry: String,  // "Italy"
    val meetingLocation: String, // "Monza"
    val circuitShortName: String,
    val sessionName: String,     // "Qualifying"
    val sessionKind: SessionKind,
    val sessionPart: Int?,       // 1/2/3 for Q1/Q2/Q3, null otherwise
    val status: SessionStatus,
    val trackFlag: TrackFlag,
    val airTempC: Double?,
    val trackTempC: Double?,
    val humidityPct: Double?,
    val rainfall: Boolean?,
    val currentLap: Int?,
    val totalLaps: Int?,
    val drivers: List<DriverTiming>,   // sorted by position ascending
    val raceControl: List<RaceControlMessage>, // newest last
    val lastUpdateUtcMillis: Long,
    val source: LiveSource = LiveSource.NONE,
) {
    companion object {
        val EMPTY = LiveSessionState(
            isConnected = false, meetingName = "", meetingCountry = "", meetingLocation = "",
            circuitShortName = "", sessionName = "", sessionKind = SessionKind.UNKNOWN,
            sessionPart = null, status = SessionStatus.UNKNOWN, trackFlag = TrackFlag.UNKNOWN,
            airTempC = null, trackTempC = null, humidityPct = null, rainfall = null,
            currentLap = null, totalLaps = null, drivers = emptyList(), raceControl = emptyList(),
            lastUpdateUtcMillis = 0L, source = LiveSource.NONE,
        )
    }

    val isLive: Boolean get() = status == SessionStatus.STARTED || status == SessionStatus.ABORTED
    val topThree: List<DriverTiming> get() = drivers.take(3)
}
