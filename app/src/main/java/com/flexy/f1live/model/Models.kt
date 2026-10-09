package com.flexy.f1live.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class SessionKind { PRACTICE1, PRACTICE2, PRACTICE3, SPRINT_QUALIFYING, SPRINT, QUALIFYING, RACE, UNKNOWN }

@Immutable
@Serializable
data class ScheduledSession(
    val kind: SessionKind,
    val name: String,
    val startUtcMillis: Long?,
)

@Immutable
@Serializable
data class RaceWeekend(
    val season: Int,
    val round: Int,
    val name: String,
    val country: String,
    val locality: String,
    val circuitName: String,
    val countryCode: String?,
    val sessions: List<ScheduledSession>,
    val circuitWikiUrl: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val raceStartUtcMillis: Long? get() = sessions.firstOrNull { it.kind == SessionKind.RACE }?.startUtcMillis
}

@Serializable
enum class SessionStatus { INACTIVE, STARTED, ABORTED, FINISHED, FINALISED, ENDS, UNKNOWN }

@Serializable
enum class LiveSource {
    NONE,
    F1_LIVE_TIMING,
    ESPN,
    JOLPICA,
    CACHE,
}

@Serializable
enum class TrackFlag { GREEN, YELLOW, SC, RED, VSC, VSC_ENDING, UNKNOWN }

@Immutable
@Serializable
data class SectorTiming(
    val value: String,
    val personalFastest: Boolean,
    val overallFastest: Boolean,
)

@Immutable
@Serializable
data class DriverTiming(
    val position: Int,
    val racingNumber: String,
    val tla: String,
    val firstName: String,
    val lastName: String,
    val shortName: String,
    val teamName: String,
    val teamColorHex: String?,
    val headshotUrl: String?,
    val countryCode: String?,
    val bestLapTime: String,
    val lastLapTime: String,
    val gapToLeader: String,
    val interval: String,
    val sectors: List<SectorTiming>,
    val inPit: Boolean,
    val pitOut: Boolean,
    val retired: Boolean,
    val stopped: Boolean,
    val knockedOut: Boolean,
    val numberOfLaps: Int,
    val numberOfPitStops: Int,
    val tyreCompound: String?,
    val fastestLap: Boolean = false,
    val bestSectors: List<SectorTiming> = emptyList(),
    val stints: List<TyreStint> = emptyList(),
) {
    val pitStops: Int
        get() = if (numberOfPitStops > 0) numberOfPitStops else (stints.size - 1).coerceAtLeast(0)

    val pitStopLaps: List<Int>
        get() = stints.dropLast(1).runningFold(0) { lap, stint -> lap + stint.laps }.drop(1)
}

@Immutable
@Serializable
data class TyreStint(
    val compound: String?,
    val isNew: Boolean,
    val laps: Int,
)

@Immutable
@Serializable
data class RaceControlMessage(
    val utcMillis: Long?,
    val category: String,
    val message: String,
    val flag: String?,
)

@Immutable
@Serializable
data class LiveSessionState(
    val isConnected: Boolean,
    val meetingName: String,
    val meetingCountry: String,
    val meetingLocation: String,
    val circuitShortName: String,
    val sessionName: String,
    val sessionKind: SessionKind,
    val sessionPart: Int?,
    val status: SessionStatus,
    val trackFlag: TrackFlag,
    val airTempC: Double?,
    val trackTempC: Double?,
    val humidityPct: Double?,
    val rainfall: Boolean?,
    val currentLap: Int?,
    val totalLaps: Int?,
    val drivers: List<DriverTiming>,
    val raceControl: List<RaceControlMessage>,
    val lastUpdateUtcMillis: Long,
    val source: LiveSource = LiveSource.NONE,
    val trackUtcOffsetMinutes: Int? = null,
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
