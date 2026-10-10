package com.flexy.f1live.data

import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.model.TrackFlag
import com.flexy.f1live.model.TyreStint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStateParserTest {

    private val state = LiveStateParser.parse(TestFixtures.snapshot(), isConnected = true)

    @Test
    fun `meeting and session identity`() {
        assertEquals("Italian Grand Prix", state.meetingName)
        assertEquals("Italy", state.meetingCountry)
        assertEquals("Monza", state.meetingLocation)
        assertEquals("Monza", state.circuitShortName)
        assertEquals("Qualifying", state.sessionName)
        assertEquals(SessionKind.QUALIFYING, state.sessionKind)
        assertEquals(2, state.sessionPart)
        assertEquals(SessionStatus.STARTED, state.status)
        assertEquals(TrackFlag.GREEN, state.trackFlag)
        assertTrue(state.isLive)
        assertTrue(state.isConnected)
    }

    @Test
    fun `weather comes back as numbers`() {
        assertEquals(33.9, state.airTempC!!, 0.001)
        assertEquals(52.9, state.trackTempC!!, 0.001)
        assertEquals(35.0, state.humidityPct!!, 0.001)
        assertEquals(false, state.rainfall)
    }

    @Test
    fun `no lap count in a qualifying session`() {
        assertNull(state.currentLap)
        assertNull(state.totalLaps)
    }

    @Test
    fun `drivers are sorted by timing line`() {
        assertEquals(22, state.drivers.size)
        assertEquals((1..22).toList(), state.drivers.map { it.position })
        assertEquals("81", state.drivers.first().racingNumber)
        assertEquals("PIA", state.drivers.first().tla)
    }

    @Test
    fun `leader carries a lap time, team colour and short name`() {
        val leader = state.drivers.first()
        assertTrue(leader.bestLapTime.isNotEmpty())
        assertEquals("1:22.017", leader.bestLapTime)
        assertEquals("O. Piastri", leader.shortName)
        assertEquals("McLaren", leader.teamName)
        assertEquals("F47600", leader.teamColorHex)
        assertNotNull(leader.headshotUrl)
        assertEquals("SOFT", leader.tyreCompound)
        assertEquals(3, leader.sectors.size)
        assertFalse(leader.knockedOut)
    }

    @Test
    fun `the fastest lap of the session is flagged on exactly one driver`() {
        // TimingStats.Lines[n].PersonalBestLapTime.Position == 1 is the purple lap of the tower.
        val holders = state.drivers.filter { it.fastestLap }
        assertEquals(1, holders.size)
        assertEquals("PIA", holders.single().tla)
        assertEquals("1:22.017", holders.single().bestLapTime)
    }

    @Test
    fun `best sectors come from TimingStats, not from the last lap`() {
        val leader = state.drivers.first()
        // TimingData.Sectors is whatever the car did on its most recent lap; TimingStats
        // .BestSectors is the session best, which is what a finished classification shows.
        assertEquals(3, leader.bestSectors.size)
        assertTrue(leader.bestSectors.all { it.value.isNotBlank() })
        assertTrue(leader.bestSectors.all { it.personalFastest })
        // Position 1 in a sector means nobody in the session went quicker.
        assertTrue(
            "the session leader holds at least one overall-best sector",
            leader.bestSectors.any { it.overallFastest },
        )
    }

    @Test
    fun `qualifying gaps come from the session part stats entry`() {
        val second = state.drivers[1]
        assertEquals("1", second.racingNumber)
        assertEquals("L. Norris", second.shortName)
        // Stats[1] is Q2 (SessionPart == 2), not Stats[0] which still holds the Q1 gap.
        assertEquals("+0.050", second.gapToLeader)
        assertEquals("+0.050", second.interval)
        assertEquals("1:22.067", second.bestLapTime)
        assertEquals(10, second.numberOfLaps)
    }

    @Test
    fun `stints come oldest first with the laps of each run`() {
        val norris = state.drivers.first { it.racingNumber == "1" }
        // TotalLaps is the age of the set, StartLaps the age it went out on: the second run was
        // on the first run's set, which already had 4 laps on it.
        assertEquals(
            listOf(
                TyreStint("SOFT", isNew = true, laps = 4),
                TyreStint("SOFT", isNew = false, laps = 3),
                TyreStint("SOFT", isNew = true, laps = 2),
            ),
            norris.stints,
        )
        assertEquals(listOf(4, 7), norris.pitStopLaps)
    }

    @Test
    fun `a stint that has only just started has no laps yet`() {
        val sainz = state.drivers.first { it.racingNumber == "55" }
        assertEquals(5, sainz.stints.size)
        assertEquals(TyreStint("SOFT", isNew = true, laps = 0), sainz.stints.last())
    }

    @Test
    fun `a race tyre history gives the lap of every stop`() {
        val state = LiveStateParser.parse(
            raceDocument(
                stints = """[
                    {"Compound": "SOFT", "New": "false", "StartLaps": 3, "TotalLaps": 18},
                    {"Compound": "MEDIUM", "New": "true", "StartLaps": 0, "TotalLaps": 23},
                    {"Compound": "HARD", "New": "true", "StartLaps": 0, "TotalLaps": 9}
                ]""",
                pitStops = 2,
            ),
        )
        val driver = state.drivers.single()
        assertEquals(listOf(15, 23, 9), driver.stints.map { it.laps })
        assertEquals(listOf(false, true, true), driver.stints.map { it.isNew })
        assertEquals(2, driver.pitStops)
        assertEquals(listOf(15, 38), driver.pitStopLaps)
    }

    @Test
    fun `stints merged from deltas arrive keyed by index`() {
        val state = LiveStateParser.parse(
            raceDocument(
                stints = """{
                    "1": {"Compound": "HARD", "New": "true", "StartLaps": 0, "TotalLaps": 4},
                    "0": {"Compound": "UNKNOWN", "New": "true", "StartLaps": 0, "TotalLaps": 12}
                }""",
                pitStops = 0,
            ),
        )
        val driver = state.drivers.single()
        assertEquals(listOf(null, "HARD"), driver.stints.map { it.compound })
        // No count on the timing line: the stint change stands in for it.
        assertEquals(1, driver.pitStops)
        assertEquals(listOf(12), driver.pitStopLaps)
    }

    private fun raceDocument(stints: String, pitStops: Int) =
        kotlinx.serialization.json.Json.parseToJsonElement(
            """{
                "SessionInfo": {"Name": "Race", "Type": "Race"},
                "TimingData": {"Lines": {"4": {"Line": 1, "NumberOfLaps": 47, "NumberOfPitStops": $pitStops}}},
                "TimingAppData": {"Lines": {"4": {"RacingNumber": "4", "Stints": $stints}}}
            }""",
        ) as kotlinx.serialization.json.JsonObject

    @Test
    fun `knocked out drivers are flagged`() {
        val eliminated = state.drivers.filter { it.knockedOut }.map { it.racingNumber }.toSet()
        assertEquals(setOf("11", "14", "18", "22", "23", "77"), eliminated)
    }

    @Test
    fun `race control messages are ordered oldest first and timestamped as UTC`() {
        assertTrue(state.raceControl.isNotEmpty())
        val first = state.raceControl.first()
        assertEquals("CLEAR IN TRACK SECTOR 6", first.message)
        assertEquals("Flag", first.category)
        assertEquals("CLEAR", first.flag)
        // "2026-09-05T13:50:42" has no zone and must be read as UTC.
        assertEquals(1788616242000L, first.utcMillis)
        val times = state.raceControl.mapNotNull { it.utcMillis }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `country codes map from three letter codes with a null fallback`() {
        assertEquals("gb", LiveStateParser.countryCodeOf("GBR"))
        assertEquals("nl", LiveStateParser.countryCodeOf("NED"))
        assertEquals("it", LiveStateParser.countryCodeOf("ITA"))
        assertEquals("ar", LiveStateParser.countryCodeOf("ARG"))
        assertEquals("mc", LiveStateParser.countryCodeOf("MON"))
        assertEquals("th", LiveStateParser.countryCodeOf("THA"))
        assertNull(LiveStateParser.countryCodeOf("ZZZ"))
        assertNull(LiveStateParser.countryCodeOf(null))
        assertNull(LiveStateParser.countryCodeOf(""))
        // this snapshot's DriverList has no CountryCode at all
        // The feed has no CountryCode; flags come from the static TLA table instead.
        assertTrue(state.drivers.all { it.countryCode != null })
        assertEquals("nl", state.drivers.first { it.tla == "VER" }.countryCode)
    }

    @Test
    fun `session kind and track flag mapping`() {
        assertEquals(SessionKind.PRACTICE1, LiveStateParser.sessionKindOf("Practice", "Practice 1"))
        assertEquals(SessionKind.PRACTICE3, LiveStateParser.sessionKindOf("Practice", "Practice 3"))
        assertEquals(SessionKind.QUALIFYING, LiveStateParser.sessionKindOf("Qualifying", "Qualifying"))
        assertEquals(
            SessionKind.SPRINT_QUALIFYING,
            LiveStateParser.sessionKindOf("Qualifying", "Sprint Qualifying"),
        )
        assertEquals(SessionKind.SPRINT, LiveStateParser.sessionKindOf("Race", "Sprint"))
        assertEquals(SessionKind.RACE, LiveStateParser.sessionKindOf("Race", "Race"))
        assertEquals(SessionKind.UNKNOWN, LiveStateParser.sessionKindOf("", ""))

        assertEquals(TrackFlag.GREEN, LiveStateParser.trackFlagOf("1"))
        assertEquals(TrackFlag.YELLOW, LiveStateParser.trackFlagOf("2"))
        assertEquals(TrackFlag.SC, LiveStateParser.trackFlagOf("4"))
        assertEquals(TrackFlag.RED, LiveStateParser.trackFlagOf("5"))
        assertEquals(TrackFlag.VSC, LiveStateParser.trackFlagOf("6"))
        assertEquals(TrackFlag.VSC_ENDING, LiveStateParser.trackFlagOf("7"))
        assertEquals(TrackFlag.UNKNOWN, LiveStateParser.trackFlagOf(null))
    }

    @Test
    fun `parsing an empty document yields an empty but connected state`() {
        val empty = LiveStateParser.parse(
            kotlinx.serialization.json.JsonObject(emptyMap()),
            isConnected = false,
        )
        assertEquals("", empty.meetingName)
        assertEquals(SessionKind.UNKNOWN, empty.sessionKind)
        assertEquals(SessionStatus.UNKNOWN, empty.status)
        assertTrue(empty.drivers.isEmpty())
        assertTrue(empty.raceControl.isEmpty())
    }
}
