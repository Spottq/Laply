package com.flexy.f1live.data

import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.LiveSource
import com.flexy.f1live.model.SectorTiming
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = JsonStore(folder.newFolder("cache-json"))

    @Test
    fun `a written session comes back identical`() = runBlocking {
        val store = store()
        store.write("last_session", LiveSessionState.serializer(), state)

        assertTrue(store.exists("last_session"))
        assertEquals(state, store.read("last_session", LiveSessionState.serializer()))
    }

    @Test
    fun `an absent key reads as null`() = runBlocking {
        assertNull(store().read("nothing_here", LiveSessionState.serializer()))
    }

    @Test
    fun `a corrupt document is dropped instead of thrown`() = runBlocking {
        val store = store()
        store.write("last_session", LiveSessionState.serializer(), state)
        store.fileOf("last_session").writeText("{\"drivers\": [ truncated")

        assertNull(store.read("last_session", LiveSessionState.serializer()))
        assertFalse("the unusable file is deleted", store.exists("last_session"))
    }

    @Test
    fun `a rewrite replaces the previous document and leaves no temp file behind`() = runBlocking {
        val store = store()
        store.write("last_session", LiveSessionState.serializer(), state)
        val second = state.copy(sessionName = "Race", drivers = state.drivers.take(1))
        store.write("last_session", LiveSessionState.serializer(), second)

        assertEquals(second, store.read("last_session", LiveSessionState.serializer()))
        val temps = store.fileOf("last_session").parentFile?.listFiles()
            ?.filter { it.name.endsWith(".tmp") }
            .orEmpty()
        assertTrue("temp files: " + temps, temps.isEmpty())
    }

    @Test
    fun `delete forgets the document`() = runBlocking {
        val store = store()
        store.write("2026_12_RACE", LiveSessionState.serializer(), state)
        store.delete("2026_12_RACE")

        assertFalse(store.exists("2026_12_RACE"))
        assertNull(store.read("2026_12_RACE", LiveSessionState.serializer()))
    }

    @Test
    fun `keys that are not file-system safe still round-trip`() = runBlocking {
        val store = store()
        store.write("2026/12 race", LiveSessionState.serializer(), state)

        assertEquals(state, store.read("2026/12 race", LiveSessionState.serializer()))
    }

    private val state = LiveSessionState(
        isConnected = false,
        meetingName = "Dutch Grand Prix",
        meetingCountry = "Netherlands",
        meetingLocation = "Zandvoort",
        circuitShortName = "Zandvoort",
        sessionName = "Qualifying",
        sessionKind = SessionKind.QUALIFYING,
        sessionPart = 3,
        status = SessionStatus.FINISHED,
        trackFlag = com.flexy.f1live.model.TrackFlag.GREEN,
        airTempC = 21.5,
        trackTempC = 33.0,
        humidityPct = null,
        rainfall = false,
        currentLap = null,
        totalLaps = null,
        drivers = listOf(
            DriverTiming(
                position = 1, racingNumber = "4", tla = "NOR", firstName = "Lando",
                lastName = "Norris", shortName = "L. Norris", teamName = "McLaren",
                teamColorHex = "F47600", headshotUrl = null, countryCode = "gb",
                bestLapTime = "1:08.662", lastLapTime = "", gapToLeader = "", interval = "",
                sectors = listOf(
                    SectorTiming("1:08.662", personalFastest = true, overallFastest = true),
                ),
                inPit = false, pitOut = false, retired = false, stopped = false,
                knockedOut = false, numberOfLaps = 18, numberOfPitStops = 0, tyreCompound = "SOFT",
            ),
            DriverTiming(
                position = 2, racingNumber = "1", tla = "VER", firstName = "Max",
                lastName = "Verstappen", shortName = "M. Verstappen", teamName = "Red Bull",
                teamColorHex = "3671C6", headshotUrl = null, countryCode = "nl",
                bestLapTime = "1:08.925", lastLapTime = "", gapToLeader = "+0.263", interval = "",
                sectors = emptyList(), inPit = false, pitOut = false, retired = false,
                stopped = false, knockedOut = false, numberOfLaps = 16, numberOfPitStops = 0,
                tyreCompound = null,
            ),
        ),
        raceControl = emptyList(),
        lastUpdateUtcMillis = 1_756_912_345_000L,
        source = LiveSource.ESPN,
    )
}
