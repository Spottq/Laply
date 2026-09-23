package com.flexy.f1live.data

import com.flexy.f1live.model.LiveSource
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.model.TrackFlag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mapping of the ESPN fallback feed; fixtures are trimmed copies of the real documents. */
class EspnMapperTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    // ------------------------------------------------------------------ fixtures

    private val scoreboard = """
        {"events":[{
          "id":"600057442",
          "name":"Pirelli Italian Grand Prix",
          "shortName":"Pirelli Italian GP",
          "date":"2026-09-04T10:30Z",
          "endDate":"2026-09-06T16:00Z",
          "circuit":{"id":"615","fullName":"Autodromo Nazionale Monza",
                     "address":{"city":"Monza","country":"Italy"}},
          "competitions":[
            {"id":"401839100","date":"2026-09-04T11:30Z","endDate":"2026-09-04T12:30Z",
             "type":{"text":"Practice 1","abbreviation":"FP1"},
             "status":{"type":{"name":"STATUS_FINAL","state":"post","completed":true}},
             "competitors":[]},
            {"id":"401839101","date":"2026-09-05T14:00Z",
             "type":{"text":"Qualifying","abbreviation":"Qual"},
             "status":{"clock":0.0,"period":4,
                       "type":{"name":"STATUS_IN_PROGRESS","state":"in","completed":false,
                               "description":"In Progress"}},
             "competitors":[
               {"id":"5503","order":1,"winner":false,
                "athlete":{"fullName":"George Russell","displayName":"George Russell",
                           "shortName":"G. Russell",
                           "flag":{"href":"https://a.espncdn.com/i/gbr.png","alt":"Britain"}},
                "statistics":[]},
               {"id":"5601","order":2,"winner":false,
                "athlete":{"fullName":"Kimi Antonelli","displayName":"Kimi Antonelli",
                           "shortName":"K. Antonelli",
                           "flag":{"href":"https://a.espncdn.com/i/ita.png","alt":"Italy"}},
                "statistics":[]},
               {"id":"4665","order":3,"winner":false,
                "athlete":{"fullName":"Max Verstappen","displayName":"Max Verstappen",
                           "shortName":"M. Verstappen",
                           "flag":{"href":"https://a.espncdn.com/i/ned.png","alt":"Netherlands"}},
                "statistics":[]}]},
            {"id":"401839102","date":"2026-09-06T13:00Z",
             "type":{"text":"Race","abbreviation":"Race"},
             "status":{"type":{"name":"STATUS_SCHEDULED","state":"pre","completed":false}},
             "competitors":[]}
          ]}]}
    """.trimIndent()

    /** `?dates=YYYY`: every event of the season, most of them already finished. */
    private val seasonScoreboard = """
        {"events":[
          {"id":"600057441","name":"Heineken Dutch Grand Prix",
           "circuit":{"fullName":"Circuit Zandvoort","address":{"city":"Zandvoort","country":"Netherlands"}},
           "competitions":[
             {"id":"401839090","date":"2026-08-29T13:00Z","type":{"text":"Qualifying","abbreviation":"Qual"},
              "status":{"type":{"state":"post"}},"competitors":[]},
             {"id":"401839091","date":"2026-08-30T13:00Z","type":{"text":"Race","abbreviation":"Race"},
              "status":{"type":{"state":"post"}},"competitors":[]}]},
          {"id":"600057442","name":"Pirelli Italian Grand Prix",
           "circuit":{"fullName":"Autodromo Nazionale Monza","address":{"city":"Monza","country":"Italy"}},
           "competitions":[
             {"id":"401839100","date":"2026-09-04T11:30Z","type":{"text":"Practice 1","abbreviation":"FP1"},
              "status":{"type":{"state":"post"}},"competitors":[]},
             {"id":"401839101","date":"2026-09-05T14:00Z","type":{"text":"Qualifying","abbreviation":"Qual"},
              "status":{"type":{"state":"post"}},
              "competitors":[
                {"id":"5503","order":1,
                 "athlete":{"fullName":"George Russell","shortName":"G. Russell",
                            "flag":{"alt":"Britain"}}}]},
             {"id":"401839102","date":"2026-09-06T13:00Z","type":{"text":"Race","abbreviation":"Race"},
              "status":{"type":{"state":"pre"}},"competitors":[]}]},
          {"id":"600057443","name":"Singapore Airlines Singapore Grand Prix",
           "circuit":{"fullName":"Marina Bay","address":{"city":"Singapore","country":"Singapore"}},
           "competitions":[
             {"id":"401839110","date":"2026-09-19T13:00Z","type":{"text":"Qualifying","abbreviation":"Qual"},
              "status":{"type":{"state":"pre"}},"competitors":[]}]}
        ]}
    """.trimIndent()

    private val competitors = """
        {"items":[
          {"id":"5503","order":1,
           "vehicle":{"number":"63","manufacturer":"Mercedes","teamColor":"00D2BE"},
           "statistics":{"${'$'}ref":"http://sports.core.api.espn.com/v2/x/competitors/5503/statistics?lang=en"},
           "status":{"${'$'}ref":"http://sports.core.api.espn.com/v2/x/competitors/5503/status?lang=en"}},
          {"id":"5601","order":2,
           "vehicle":{"number":"12","manufacturer":"Mercedes","teamColor":"00D2BE"},
           "statistics":{"${'$'}ref":"http://sports.core.api.espn.com/v2/x/competitors/5601/statistics"},
           "status":{"${'$'}ref":"http://sports.core.api.espn.com/v2/x/competitors/5601/status"}},
          {"id":"4665","order":3,
           "vehicle":{"number":"1","manufacturer":"Red Bull","teamColor":"3671C6"},
           "statistics":{"${'$'}ref":"http://sports.core.api.espn.com/v2/x/competitors/4665/statistics"},
           "status":{"${'$'}ref":"http://sports.core.api.espn.com/v2/x/competitors/4665/status"}}
        ]}
    """.trimIndent()

    /** A finished qualifying: all three parts published. */
    private val qualifyingStats = """
        {"splits":{"categories":[
          {"name":"general","stats":[
            {"name":"lapsCompleted","displayValue":"14"},
            {"name":"place","displayValue":"1"},
            {"name":"qual1TimeMS","displayValue":"1:12.695"},
            {"name":"qual2TimeMS","displayValue":"1:11.628"},
            {"name":"qual3TimeMS","displayValue":"1:11.163"},
            {"name":"totalTime","displayValue":"1:11.163"},
            {"name":"pitsTaken","displayValue":"0"}]},
          {"name":"gapToLeader","stats":[
            {"name":"position","displayValue":"1"},
            {"name":"lastUpdated","displayValue":"2026-09-05T14:53:48Z"}]}]}}
    """.trimIndent()

    /** Q3 is still running, so its time is not published yet. */
    private val qualifyingStatsQ2Only = """
        {"splits":{"categories":[{"name":"general","stats":[
          {"name":"lapsCompleted","displayValue":"9"},
          {"name":"qual1TimeMS","displayValue":"1:12.940"},
          {"name":"qual2TimeMS","displayValue":"1:11.902"},
          {"name":"qual3TimeMS","displayValue":"0.000"},
          {"name":"pitsTaken","displayValue":"0"}]}]}}
    """.trimIndent()

    private val raceStats = """
        {"splits":{"categories":[{"name":"general","stats":[
          {"name":"lapsCompleted","displayValue":"53"},
          {"name":"place","displayValue":"2"},
          {"name":"totalTime","displayValue":"1:13:24.325"},
          {"name":"behindTime","displayValue":"+11.536"},
          {"name":"pitsTaken","displayValue":"3"}]}]}}
    """.trimIndent()

    // ------------------------------------------------------------------ scoreboard

    @Test
    fun `scoreboard flattens competitions and strips the sponsor prefix`() {
        val all = EspnMapper.parseScoreboard(obj(scoreboard))
        assertEquals(3, all.size)
        assertEquals(listOf("Practice 1", "Qualifying", "Race"), all.map { it.sessionName })
        assertEquals(listOf("post", "in", "pre"), all.map { it.state })
        all.forEach {
            assertEquals("Italian Grand Prix", it.meetingName)
            assertEquals("Italy", it.country)
            assertEquals("Monza", it.city)
            assertEquals("600057442", it.eventId)
        }
        assertEquals(SessionKind.PRACTICE1, all[0].sessionKind)
        assertEquals(SessionKind.QUALIFYING, all[1].sessionKind)
        assertEquals(SessionKind.RACE, all[2].sessionKind)
    }

    @Test
    fun `sponsor stripping keeps multi word qualifiers and untouched names`() {
        assertEquals("Italian Grand Prix", EspnMapper.stripSponsor("Pirelli Italian Grand Prix"))
        assertEquals("Italian Grand Prix", EspnMapper.stripSponsor("Italian Grand Prix"))
        assertEquals(
            "United States Grand Prix",
            EspnMapper.stripSponsor("Formula 1 Pirelli United States Grand Prix"),
        )
        assertEquals("Abu Dhabi Grand Prix", EspnMapper.stripSponsor("Etihad Abu Dhabi Grand Prix"))
        assertEquals("Miami Grand Prix", EspnMapper.stripSponsor("Crypto.com Miami Grand Prix"))
        assertEquals("Las Vegas Grand Prix", EspnMapper.stripSponsor("Heineken Las Vegas Grand Prix"))
        assertEquals("Sao Paulo Grand Prix", EspnMapper.stripSponsor("Rolex Sao Paulo Grand Prix"))
        assertEquals("Bahrain Sakhir Test", EspnMapper.stripSponsor("Bahrain Sakhir Test"))
    }

    @Test
    fun `session names follow the competition abbreviation`() {
        assertEquals("Practice 1", EspnMapper.sessionNameOf("Practice 1", "FP1"))
        assertEquals("Practice 3", EspnMapper.sessionNameOf("Practice 3", "FP3"))
        assertEquals("Sprint Qualifying", EspnMapper.sessionNameOf("Sprint Shootout", "SS"))
        assertEquals("Sprint", EspnMapper.sessionNameOf("Sprint Race", "SR"))
        assertEquals("Qualifying", EspnMapper.sessionNameOf("Qualifying", "Qual"))
        assertEquals("Race", EspnMapper.sessionNameOf("Race", "Race"))
        assertEquals("Warm Up", EspnMapper.sessionNameOf("Warm Up", "WU"))
        assertEquals(
            SessionKind.SPRINT_QUALIFYING,
            LiveStateParser.sessionKindOf("Sprint Shootout", EspnMapper.sessionNameOf("Sprint Shootout", "SS")),
        )
        assertEquals(
            SessionKind.SPRINT,
            LiveStateParser.sessionKindOf("Sprint Race", EspnMapper.sessionNameOf("Sprint Race", "SR")),
        )
    }

    @Test
    fun `the live competition is picked while a session runs`() {
        val all = EspnMapper.parseScoreboard(obj(scoreboard))
        assertEquals("401839101", EspnMapper.pickLive(all)!!.competitionId)
        assertEquals(SessionKind.QUALIFYING, EspnMapper.pickLive(all)!!.sessionKind)
    }

    @Test
    fun `nothing is live when every competition is scheduled or finished`() {
        val all = EspnMapper.parseScoreboard(obj(scoreboard)).filter { it.state != "in" }
        assertNull(EspnMapper.pickLive(all))
    }

    @Test
    fun `the last completed session is the newest finished one of the newest event`() {
        val season = EspnMapper.parseScoreboard(obj(seasonScoreboard))
        val last = EspnMapper.pickLastCompleted(season)!!
        // Monza's qualifying is finished and newer than anything at Zandvoort; the Monza race is
        // still scheduled, so it must not win.
        assertEquals("401839101", last.competitionId)
        assertEquals("Qualifying", last.sessionName)
        assertEquals("Italian Grand Prix", last.meetingName)
        assertEquals(SessionStatus.FINISHED, EspnMapper.statusOf(last.state))
    }

    @Test
    fun `no finished session at all yields nothing`() {
        val pre = EspnMapper.parseScoreboard(obj(seasonScoreboard)).filter { it.state == "pre" }
        assertNull(EspnMapper.pickLastCompleted(pre))
    }

    // ------------------------------------------------------------------ core API

    @Test
    fun `competitors carry car numbers, teams, colours and https refs`() {
        val vehicles = EspnMapper.parseCompetitors(obj(competitors))
        assertEquals(3, vehicles.size)
        val russell = vehicles.first { it.competitorId == "5503" }
        assertEquals("63", russell.number)
        assertEquals("Mercedes", russell.manufacturer)
        assertEquals("00D2BE", russell.teamColorHex)
        assertEquals(1, russell.order)
        assertTrue(russell.statisticsRef!!.startsWith("https://sports.core.api.espn.com/"))
        assertTrue(russell.statusRef!!.startsWith("https://sports.core.api.espn.com/"))
    }

    @Test
    fun `statistics are flattened across categories`() {
        val stats = EspnMapper.parseStatistics(obj(qualifyingStats))
        assertEquals("14", stats["lapsCompleted"])
        assertEquals("1:11.163", stats["qual3TimeMS"])
        assertEquals("1", stats["position"]) // from the gapToLeader category
        assertEquals("2026-09-05T14:53:48Z", stats["lastUpdated"])
    }

    @Test
    fun `competitor status maps name and lap count`() {
        val status = EspnMapper.parseCompetitorStatus(
            obj("""{"period":14,"type":{"name":"STATUS_ON_TRACK","description":"On Track"}}"""),
        )
        assertEquals("STATUS_ON_TRACK", status.name)
        assertEquals(14, status.period)
    }

    // ------------------------------------------------------------------ state

    @Test
    fun `qualifying maps to a live session state`() {
        val competition = EspnMapper.parseScoreboard(obj(scoreboard)).first { it.state == "in" }
        val vehicles = EspnMapper.parseCompetitors(obj(competitors)).associateBy { it.competitorId }
        val state = EspnMapper.buildState(
            competition = competition,
            vehicles = vehicles,
            statistics = mapOf(
                "5503" to EspnMapper.parseStatistics(obj(qualifyingStats)),
                "5601" to EspnMapper.parseStatistics(obj(qualifyingStatsQ2Only)),
            ),
            statuses = mapOf(
                "5503" to EspnMapper.CompetitorStatus("STATUS_ON_TRACK", 14),
                "5601" to EspnMapper.CompetitorStatus("STATUS_IN_PITS", 9),
                "4665" to EspnMapper.CompetitorStatus("STATUS_RETIRED", 3),
            ),
            nowUtcMillis = 1_700_000_000_000L,
        )

        assertEquals(LiveSource.ESPN, state.source)
        assertTrue(state.isConnected)
        assertEquals("Italian Grand Prix", state.meetingName)
        assertEquals("Italy", state.meetingCountry)
        assertEquals("Monza", state.meetingLocation)
        assertEquals("Monza", state.circuitShortName)
        assertEquals("Qualifying", state.sessionName)
        assertEquals(SessionKind.QUALIFYING, state.sessionKind)
        assertNull(state.sessionPart)
        assertEquals(SessionStatus.STARTED, state.status)
        assertEquals(TrackFlag.UNKNOWN, state.trackFlag)
        assertNull(state.airTempC)
        assertNull(state.currentLap)
        assertTrue(state.raceControl.isEmpty())
        assertEquals(1_700_000_000_000L, state.lastUpdateUtcMillis)

        assertEquals(listOf(1, 2, 3), state.drivers.map { it.position })
        val russell = state.drivers[0]
        assertEquals("RUS", russell.tla)
        assertEquals("63", russell.racingNumber)
        assertEquals("George", russell.firstName)
        assertEquals("Russell", russell.lastName)
        assertEquals("G. Russell", russell.shortName)
        assertEquals("Mercedes", russell.teamName)
        assertEquals("00D2BE", russell.teamColorHex)
        assertEquals(DriverHeadshots.forTla("RUS"), russell.headshotUrl)
        assertEquals("gb", russell.countryCode)
        assertEquals("1:11.163", russell.bestLapTime) // Q3 published
        assertEquals("", russell.gapToLeader)         // never a gap in qualifying
        assertEquals("", russell.interval)
        assertEquals(14, russell.numberOfLaps)
        assertFalse(russell.inPit)
        assertFalse(russell.retired)
        assertFalse(russell.knockedOut)
        assertNull(russell.tyreCompound)
        assertTrue(russell.sectors.isEmpty())

        val antonelli = state.drivers[1]
        assertEquals("ANT", antonelli.tla)
        assertEquals("12", antonelli.racingNumber)
        assertEquals("it", antonelli.countryCode)
        assertEquals("1:11.902", antonelli.bestLapTime) // Q3 still "0.000" -> Q2 time
        assertTrue(antonelli.inPit)

        val verstappen = state.drivers[2]
        assertEquals("VER", verstappen.tla)
        assertEquals("nl", verstappen.countryCode)
        assertEquals("", verstappen.bestLapTime) // no statistics document at all
        assertTrue(verstappen.retired)
        assertEquals(3, verstappen.numberOfLaps) // falls back to the status period
    }

    @Test
    fun `races show the gap to the leader instead of a lap time`() {
        val qualifying = EspnMapper.parseScoreboard(obj(scoreboard)).first { it.state == "in" }
        val race = qualifying.copy(
            sessionName = "Race",
            sessionKind = SessionKind.RACE,
            state = "post",
        )
        val state = EspnMapper.buildState(
            competition = race,
            vehicles = EspnMapper.parseCompetitors(obj(competitors)).associateBy { it.competitorId },
            statistics = mapOf("5601" to EspnMapper.parseStatistics(obj(raceStats))),
            statuses = emptyMap(),
            nowUtcMillis = 1L,
        )

        assertEquals(SessionStatus.FINISHED, state.status)
        val antonelli = state.drivers.first { it.racingNumber == "12" }
        assertEquals("+11.536", antonelli.gapToLeader)
        assertEquals("", antonelli.bestLapTime)
        assertEquals(53, antonelli.numberOfLaps)
        assertEquals(3, antonelli.numberOfPitStops)
    }

    @Test
    fun `a completed session still marked in progress reads as finished`() {
        // ESPN keeps state "in" for a while after the flag and only flips the type name.
        assertEquals(SessionStatus.FINISHED, EspnMapper.statusOf("in", "STATUS_SESSION_COMPLETE"))
        assertEquals(SessionStatus.FINISHED, EspnMapper.statusOf("post", "STATUS_FINAL"))
        assertEquals(SessionStatus.STARTED, EspnMapper.statusOf("in", "STATUS_IN_PROGRESS"))
        assertEquals(SessionStatus.INACTIVE, EspnMapper.statusOf("pre", "STATUS_SCHEDULED"))
        assertEquals(SessionStatus.UNKNOWN, EspnMapper.statusOf("", ""))

        val competition = EspnMapper.parseScoreboard(obj(scoreboard)).first { it.state == "in" }
        assertEquals("STATUS_IN_PROGRESS", competition.statusName)
        val state = EspnMapper.buildState(
            competition = competition.copy(statusName = "STATUS_SESSION_COMPLETE"),
            vehicles = EspnMapper.parseCompetitors(obj(competitors)).associateBy { it.competitorId },
            statistics = emptyMap(),
            statuses = emptyMap(),
            nowUtcMillis = 1L,
        )
        assertEquals(SessionStatus.FINISHED, state.status)
        assertFalse(state.isLive)
        assertEquals(3, state.drivers.size)
    }

    @Test
    fun `tla takes the last name, ignoring suffixes`() {
        assertEquals("RUS", EspnMapper.tlaOf("George Russell"))
        assertEquals("ANT", EspnMapper.tlaOf("Andrea Kimi Antonelli"))
        assertEquals("ANT", EspnMapper.tlaOf("Antonelli"))
        assertEquals("SAI", EspnMapper.tlaOf("Carlos Sainz Jr."))
        assertEquals("HUL", EspnMapper.tlaOf("Nico Hulkenberg"))
        assertEquals("", EspnMapper.tlaOf(""))
    }

    @Test
    fun `flag alt text resolves to a country code`() {
        assertEquals("gb", EspnMapper.countryCodeOfFlagAlt("Britain"))
        assertEquals("nl", EspnMapper.countryCodeOfFlagAlt("Netherlands"))
        assertEquals("nz", EspnMapper.countryCodeOfFlagAlt("New Zealand"))
        assertNull(EspnMapper.countryCodeOfFlagAlt("Atlantis"))
        assertNull(EspnMapper.countryCodeOfFlagAlt(null))
    }

    @Test
    fun `espn timestamps parse with and without seconds`() {
        assertEquals(millis("2026-09-05T14:00:00Z"), EspnMapper.parseUtcMillis("2026-09-05T14:00Z"))
        assertEquals(millis("2026-09-05T14:00:30Z"), EspnMapper.parseUtcMillis("2026-09-05T14:00:30Z"))
        assertNull(EspnMapper.parseUtcMillis(null))
        assertNull(EspnMapper.parseUtcMillis("not a date"))
    }

    private fun millis(instant: String): Long = java.time.Instant.parse(instant).toEpochMilli()

    // ------------------------------------------------- flag, lap count and the live race gap

    /** `.../competitions/{id}/status` - the only ESPN document that carries the track flag. */
    private val competitionStatus = """
        {"clock":0.0,"displayClock":"0:00","period":4,
         "type":{"id":"2","name":"STATUS_IN_PROGRESS","state":"in","completed":false},
         "flag":"RED"}
    """.trimIndent()

    /** A race in progress: ESPN publishes the gap under `gapToLeader`, not `behindTime`. */
    private val liveRaceStats = """
        {"splits":{"categories":[
          {"name":"general","stats":[
            {"name":"lapsCompleted","displayValue":"4"},
            {"name":"place","displayValue":"2"},
            {"name":"pitsTaken","displayValue":"1"}]},
          {"name":"gapToLeader","stats":[
            {"name":"position","displayValue":"2"},
            {"name":"gapToLeader","displayValue":"+5.167"},
            {"name":"lastUpdated","displayValue":"2026-09-06T13:14:26Z"}]}]}}
    """.trimIndent()

    @Test
    fun `the scoreboard carries the circuit id`() {
        assertEquals("615", EspnMapper.parseScoreboard(obj(scoreboard)).first().circuitId)
    }

    @Test
    fun `competition status yields the flag and the lap`() {
        val status = EspnMapper.parseCompetitionStatus(obj(competitionStatus))
        assertEquals("RED", status.flag)
        assertEquals(4, status.period)
        assertEquals("STATUS_IN_PROGRESS", status.statusName)
    }

    @Test
    fun `espn flag names map onto the track flag`() {
        assertEquals(TrackFlag.RED, EspnMapper.trackFlagOf("RED"))
        assertEquals(TrackFlag.GREEN, EspnMapper.trackFlagOf("green"))
        assertEquals(TrackFlag.YELLOW, EspnMapper.trackFlagOf("Yellow"))
        assertEquals(TrackFlag.SC, EspnMapper.trackFlagOf("Safety Car"))
        assertEquals(TrackFlag.UNKNOWN, EspnMapper.trackFlagOf(null))
    }

    @Test
    fun `the circuit document yields the race distance in laps`() {
        val circuit = """{"id":"615","fullName":"Autodromo Nazionale Monza","laps":53,"turns":11}"""
        assertEquals(53, EspnMapper.parseCircuitLaps(obj(circuit)))
        assertNull(EspnMapper.parseCircuitLaps(obj("""{"id":"615"}""")))
    }

    @Test
    fun `a live race maps the gap, the flag and the lap count`() {
        // The scoreboard's race entry has no roster yet, so reuse the one that has drivers.
        val competition = EspnMapper.parseScoreboard(obj(scoreboard)).first { it.state == "in" }
            .copy(sessionName = "Race", sessionKind = SessionKind.RACE)
        val state = EspnMapper.buildState(
            competition = competition,
            vehicles = EspnMapper.parseCompetitors(obj(competitors)).associateBy { it.competitorId },
            statistics = mapOf("5601" to EspnMapper.parseStatistics(obj(liveRaceStats))),
            statuses = emptyMap(),
            nowUtcMillis = 1_700_000_000_000L,
            competitionStatus = EspnMapper.parseCompetitionStatus(obj(competitionStatus)),
            totalLaps = 53,
        )

        assertEquals(TrackFlag.RED, state.trackFlag)
        assertEquals(4, state.currentLap)
        assertEquals(53, state.totalLaps)
        assertEquals("+5.167", state.drivers.first { it.tla == "ANT" }.gapToLeader)
    }

    @Test
    fun `the lap never runs past the race distance after the flag`() {
        val competition = EspnMapper.parseScoreboard(obj(scoreboard)).first { it.state == "in" }
            .copy(sessionName = "Race", sessionKind = SessionKind.RACE)
        val state = EspnMapper.buildState(
            competition = competition,
            vehicles = EspnMapper.parseCompetitors(obj(competitors)).associateBy { it.competitorId },
            statistics = mapOf("5601" to EspnMapper.parseStatistics(obj(liveRaceStats))),
            statuses = emptyMap(),
            nowUtcMillis = 1_700_000_000_000L,
            // `period` reads 4 here: a 3-lap race is over and must still say 3/3.
            competitionStatus = EspnMapper.parseCompetitionStatus(obj(competitionStatus)),
            totalLaps = 3,
        )
        assertEquals(3, state.currentLap)
    }
}
