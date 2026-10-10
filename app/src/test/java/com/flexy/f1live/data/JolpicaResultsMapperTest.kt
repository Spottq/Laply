package com.flexy.f1live.data

import com.flexy.f1live.model.LiveSource
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JolpicaResultsMapperTest {

    private val raceSession = ScheduledSession(SessionKind.RACE, "Race", 1_756_992_000_000L)
    private val qualifyingSession =
        ScheduledSession(SessionKind.QUALIFYING, "Qualifying", 1_756_905_600_000L)

    @Test
    fun `race results map positions gaps laps and retirements`() {
        val state = JolpicaResultsMapper.parse(RACE_JSON, raceSession, NOW)!!

        assertEquals("Dutch Grand Prix", state.meetingName)
        assertEquals("Netherlands", state.meetingCountry)
        assertEquals(SessionStatus.FINISHED, state.status)
        assertEquals(LiveSource.JOLPICA, state.source)
        assertEquals(SessionKind.RACE, state.sessionKind)
        assertEquals(5, state.drivers.size)
        assertEquals(listOf(1, 2, 3, 4, 5), state.drivers.map { it.position })
        assertEquals(72, state.totalLaps)

        val winner = state.drivers[0]
        assertEquals("Piastri", winner.lastName)
        assertEquals("O. Piastri", winner.shortName)
        assertEquals("McLaren", winner.teamName)
        assertEquals("F47600", winner.teamColorHex)
        assertEquals("au", winner.countryCode)
        assertEquals("", winner.gapToLeader)
        assertEquals("1:38:29.849", winner.lastLapTime)
        assertEquals("1:12.271", winner.bestLapTime)
        assertEquals(72, winner.numberOfLaps)
        assertFalse(winner.retired)

        assertEquals("+1.271", state.drivers[1].gapToLeader)
    }

    @Test
    fun `a lapped finisher keeps its status as the gap and is not marked retired`() {
        val lapped = JolpicaResultsMapper.parse(RACE_JSON, raceSession, NOW)!!.drivers[2]

        assertEquals("+1 Lap", lapped.gapToLeader)
        assertEquals(71, lapped.numberOfLaps)
        assertFalse("a lapped car is still classified", lapped.retired)
    }

    @Test
    fun `a collision is retired and shows why`() {
        val out = JolpicaResultsMapper.parse(RACE_JSON, raceSession, NOW)!!.drivers[3]

        assertTrue(out.retired)
        assertEquals("Collision", out.gapToLeader)
    }

    @Test
    fun `the rank one fastest lap is reported separately`() {
        assertEquals("81", JolpicaResultsMapper.fastestLapRacingNumber(RACE_JSON))
        assertNull(JolpicaResultsMapper.fastestLapRacingNumber(QUALIFYING_JSON))
    }

    @Test
    fun `classified statuses cover Finished, Lapped and lap deficits`() {
        assertTrue(JolpicaResultsMapper.isClassifiedStatus("Finished"))
        assertTrue(JolpicaResultsMapper.isClassifiedStatus("+2 Laps"))
        assertTrue(JolpicaResultsMapper.isClassifiedStatus("Lapped"))
        assertFalse(JolpicaResultsMapper.isClassifiedStatus("Collision"))
        assertFalse(JolpicaResultsMapper.isClassifiedStatus("Engine"))
    }

    @Test
    fun `a Lapped finisher shows its lap deficit, not the published time gap`() {
        val lapped = JolpicaResultsMapper.parse(RACE_JSON, raceSession, NOW)!!.drivers[4]

        assertEquals("HUL", lapped.tla)
        assertEquals(70, lapped.numberOfLaps)
        assertEquals("+2 Laps", lapped.gapToLeader)
        assertFalse("Lapped is a finish, not a retirement", lapped.retired)
    }

    @Test
    fun `qualifying maps Q1 Q2 Q3 into the sector slots`() {
        val state = JolpicaResultsMapper.parse(QUALIFYING_JSON, qualifyingSession, NOW)!!

        assertEquals(SessionKind.QUALIFYING, state.sessionKind)
        assertEquals(3, state.sessionPart)
        assertEquals(3, state.drivers.size)

        val pole = state.drivers[0]
        assertEquals("NOR", pole.tla)
        assertEquals("1:08.662", pole.bestLapTime)
        assertEquals(listOf("1:09.123", "1:08.900", "1:08.662"), pole.sectors.map { it.value })
        assertFalse(pole.knockedOut)

        val knockedOut = state.drivers[2]
        assertEquals(11, knockedOut.position)
        assertTrue("everyone outside the top ten was eliminated early", knockedOut.knockedOut)
        assertEquals("1:09.980", knockedOut.bestLapTime)
        assertEquals("", knockedOut.sectors[2].value)
    }

    @Test
    fun `an empty payload maps to nothing rather than an empty classification`() {
        val empty = "{\"MRData\":{\"RaceTable\":{\"Races\":[]}}}"
        assertNull(JolpicaResultsMapper.parse(empty, raceSession, NOW))
        assertNotNull(JolpicaResultsMapper.parse(RACE_JSON, raceSession, NOW))
    }

    private companion object {
        const val NOW = 1_757_000_000_000L

        val RACE_JSON = """
        {"MRData":{"RaceTable":{"season":"2026","round":"12","Races":[{
          "season":"2026","round":"12","raceName":"Dutch Grand Prix",
          "Circuit":{"circuitId":"zandvoort","url":"https://en.wikipedia.org/wiki/Circuit_Zandvoort",
            "circuitName":"Circuit Park Zandvoort",
            "Location":{"locality":"Zandvoort","country":"Netherlands"}},
          "date":"2026-08-23","time":"13:00:00Z",
          "Results":[
            {"number":"81","position":"1","positionText":"1","points":"25",
             "Driver":{"driverId":"piastri","permanentNumber":"81","code":"PIA",
               "givenName":"Oscar","familyName":"Piastri","nationality":"Australian"},
             "Constructor":{"constructorId":"mclaren","name":"McLaren","nationality":"British"},
             "grid":"1","laps":"72","status":"Finished",
             "Time":{"millis":"5909849","time":"1:38:29.849"},
             "FastestLap":{"rank":"1","lap":"60","Time":{"time":"1:12.271"}}},
            {"number":"1","position":"2","positionText":"2","points":"18",
             "Driver":{"driverId":"max_verstappen","permanentNumber":"1","code":"VER",
               "givenName":"Max","familyName":"Verstappen","nationality":"Dutch"},
             "Constructor":{"constructorId":"red_bull","name":"Red Bull","nationality":"Austrian"},
             "grid":"3","laps":"72","status":"Finished",
             "Time":{"millis":"5911120","time":"+1.271"},
             "FastestLap":{"rank":"3","lap":"70","Time":{"time":"1:12.921"}}},
            {"number":"31","position":"3","positionText":"3","points":"15",
             "Driver":{"driverId":"ocon","permanentNumber":"31","code":"OCO",
               "givenName":"Esteban","familyName":"Ocon","nationality":"French"},
             "Constructor":{"constructorId":"haas","name":"Haas F1 Team","nationality":"American"},
             "grid":"11","laps":"71","status":"+1 Lap"},
            {"number":"27","position":"5","positionText":"5","points":"10",
             "Driver":{"driverId":"hulkenberg","permanentNumber":"27","code":"HUL",
               "givenName":"Nico","familyName":"Hulkenberg","nationality":"German"},
             "Constructor":{"constructorId":"sauber","name":"Kick Sauber","nationality":"Swiss"},
             "grid":"9","laps":"70","status":"Lapped","Time":{"millis":"5945000","time":"+36.049"}},
            {"number":"16","position":"4","positionText":"R","points":"0",
             "Driver":{"driverId":"leclerc","permanentNumber":"16","code":"LEC",
               "givenName":"Charles","familyName":"Leclerc","nationality":"Monegasque"},
             "Constructor":{"constructorId":"ferrari","name":"Ferrari","nationality":"Italian"},
             "grid":"6","laps":"52","status":"Collision"}
          ]}]}}}
        """.trimIndent()

        val QUALIFYING_JSON = """
        {"MRData":{"RaceTable":{"season":"2026","round":"12","Races":[{
          "season":"2026","round":"12","raceName":"Dutch Grand Prix",
          "Circuit":{"circuitId":"zandvoort","circuitName":"Circuit Park Zandvoort",
            "Location":{"locality":"Zandvoort","country":"Netherlands"}},
          "date":"2026-08-22","time":"14:00:00Z",
          "QualifyingResults":[
            {"number":"4","position":"1",
             "Driver":{"driverId":"norris","permanentNumber":"4","code":"NOR",
               "givenName":"Lando","familyName":"Norris","nationality":"British"},
             "Constructor":{"constructorId":"mclaren","name":"McLaren","nationality":"British"},
             "Q1":"1:09.123","Q2":"1:08.900","Q3":"1:08.662"},
            {"number":"1","position":"2",
             "Driver":{"driverId":"max_verstappen","permanentNumber":"1","code":"VER",
               "givenName":"Max","familyName":"Verstappen","nationality":"Dutch"},
             "Constructor":{"constructorId":"red_bull","name":"Red Bull","nationality":"Austrian"},
             "Q1":"1:09.400","Q2":"1:09.010","Q3":"1:08.925"},
            {"number":"27","position":"11",
             "Driver":{"driverId":"hulkenberg","permanentNumber":"27","code":"HUL",
               "givenName":"Nico","familyName":"Hulkenberg","nationality":"German"},
             "Constructor":{"constructorId":"sauber","name":"Kick Sauber","nationality":"Swiss"},
             "Q1":"1:09.980"}
          ]}]}}}
        """.trimIndent()
    }
}
