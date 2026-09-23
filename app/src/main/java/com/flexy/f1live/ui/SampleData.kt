package com.flexy.f1live.ui

import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.RaceControlMessage
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SectorTiming
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.model.TrackFlag

/** Hardcoded fixtures for @Preview only. Values mirror docs/subscribe_snapshot.json. */
object SampleData {

    private fun headshot(reference: String, first: String, last: String): String {
        val initial = first.first().uppercase()
        return "https://media.formula1.com/d_driver_fallback_image.png/content/dam/fom-website/" +
            "drivers/" + initial + "/" + reference + "_" + first + "_" + last + "/" +
            reference.lowercase() + ".png.transform/1col/image.png"
    }

    private fun driver(
        position: Int,
        number: String,
        tla: String,
        first: String,
        last: String,
        team: String,
        color: String,
        country: String,
        reference: String,
        best: String,
        gap: String,
        interval: String,
        sectors: List<Triple<String, Boolean, Boolean>>,
        inPit: Boolean = false,
        knockedOut: Boolean = false,
        retired: Boolean = false,
        tyre: String? = null,
    ) = DriverTiming(
        position = position,
        racingNumber = number,
        tla = tla,
        firstName = first,
        lastName = last,
        shortName = first.first().toString() + ". " + last,
        teamName = team,
        teamColorHex = color,
        headshotUrl = headshot(reference, first, last),
        countryCode = country,
        bestLapTime = best,
        lastLapTime = best,
        gapToLeader = gap,
        interval = interval,
        sectors = sectors.map { SectorTiming(it.first, personalFastest = it.second, overallFastest = it.third) },
        inPit = inPit,
        pitOut = false,
        retired = retired,
        stopped = false,
        knockedOut = knockedOut,
        numberOfLaps = 9,
        numberOfPitStops = 0,
        tyreCompound = tyre,
    )

    val drivers: List<DriverTiming> = listOf(
        driver(1, "10", "GAS", "Pierre", "Gasly", "Alpine", "00A1E8", "fr", "PIEGAS01",
            "1:22.612", "", "",
            listOf(Triple("26.104", true, true), Triple("30.216", true, false), Triple("26.292", true, false)),
            tyre = "SOFT"),
        driver(2, "3", "VER", "Max", "Verstappen", "Red Bull Racing", "4781D7", "nl", "MAXVER01",
            "1:22.631", "+0.019", "+0.019",
            listOf(Triple("26.180", true, false), Triple("30.147", true, true), Triple("26.304", false, false)),
            tyre = "SOFT"),
        driver(3, "4", "NOR", "Lando", "Norris", "McLaren", "F47600", "gb", "LANNOR01",
            "1:22.659", "+0.047", "+0.028",
            listOf(Triple("26.166", true, false), Triple("30.203", false, false), Triple("26.290", true, true)),
            tyre = "SOFT"),
        driver(4, "43", "COL", "Franco", "Colapinto", "Alpine", "00A1E8", "ar", "FRACOL01",
            "1:22.662", "+0.050", "+0.003",
            listOf(Triple("26.212", true, false), Triple("30.238", true, false), Triple("26.212", false, false)),
            tyre = "SOFT"),
        driver(5, "37", "LIN", "Arvid", "Lindblad", "Racing Bulls", "6C98FF", "gb", "ARVLIN01",
            "1:22.727", "+0.115", "+0.065",
            listOf(Triple("26.244", false, false), Triple("30.281", true, false), Triple("26.202", true, false)),
            inPit = true, tyre = "MEDIUM"),
        driver(6, "12", "ANT", "Andrea Kimi", "Antonelli", "Mercedes", "00D7B6", "it", "ANDANT01",
            "1:22.758", "+0.146", "+0.031",
            listOf(Triple("26.259", true, false), Triple("30.295", false, false), Triple("26.204", true, false)),
            tyre = "SOFT"),
        driver(7, "63", "RUS", "George", "Russell", "Mercedes", "00D7B6", "gb", "GEORUS01",
            "1:22.779", "+0.167", "+0.021",
            listOf(Triple("26.281", false, false), Triple("30.288", true, false), Triple("26.210", false, false)),
            tyre = "SOFT"),
        driver(8, "44", "HAM", "Lewis", "Hamilton", "Ferrari", "ED1131", "gb", "LEWHAM01",
            "1:22.847", "+0.235", "+0.068",
            listOf(Triple("26.310", false, false), Triple("30.301", false, false), Triple("26.236", true, false)),
            knockedOut = true, tyre = "HARD"),
    )

    val raceControl: List<RaceControlMessage> = listOf(
        RaceControlMessage(1757083600000L, "Flag", "GREEN LIGHT - PIT EXIT OPEN", "GREEN"),
        RaceControlMessage(1757083900000L, "Flag", "YELLOW IN TRACK SECTOR 4", "YELLOW"),
        RaceControlMessage(1757084100000L, "Other", "TRACK LIMITS TURN 11 LAP 6 DELETED", null),
        RaceControlMessage(1757084400000L, "Flag", "CLEAR IN TRACK SECTOR 4", "GREEN"),
        RaceControlMessage(1757084700000L, "Drs", "DRS ENABLED", null),
    )

    val liveState: LiveSessionState = LiveSessionState(
        isConnected = true,
        meetingName = "Italian Grand Prix",
        meetingCountry = "Italy",
        meetingLocation = "Monza",
        circuitShortName = "Monza",
        sessionName = "Qualifying",
        sessionKind = SessionKind.QUALIFYING,
        sessionPart = 2,
        status = SessionStatus.STARTED,
        trackFlag = TrackFlag.GREEN,
        airTempC = 34.0,
        trackTempC = 48.0,
        humidityPct = 41.0,
        rainfall = false,
        currentLap = null,
        totalLaps = null,
        drivers = drivers,
        raceControl = raceControl,
        lastUpdateUtcMillis = 1757084700000L,
    )

    val weekend: RaceWeekend = RaceWeekend(
        season = 2026,
        round = 16,
        name = "Italian Grand Prix",
        country = "Italy",
        locality = "Monza",
        circuitName = "Autodromo Nazionale di Monza",
        countryCode = "it",
        sessions = listOf(
            ScheduledSession(SessionKind.PRACTICE1, "Practice 1", 1756908000000L),
            ScheduledSession(SessionKind.PRACTICE2, "Practice 2", 1756922400000L),
            ScheduledSession(SessionKind.PRACTICE3, "Practice 3", 1756994400000L),
            ScheduledSession(SessionKind.QUALIFYING, "Qualifying", 1757008800000L),
            ScheduledSession(SessionKind.RACE, "Race", 1757088000000L),
        ),
    )
}
