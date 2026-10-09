package com.flexy.f1live.widget.standings

import com.flexy.f1live.data.ConstructorStanding
import com.flexy.f1live.data.DriverStanding
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.widget.standings.StandingsWidgetPlanner.tablePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandingsWidgetPlannerTest {

    private val minute = 60_000L
    private val hour = StandingsWidgetPlanner.HOUR_MS
    private val day = StandingsWidgetPlanner.DAY_MS

    private val start = 1_790_000_000_000L

    private fun weekend(round: Int, vararg sessions: ScheduledSession) = RaceWeekend(
        season = 2026,
        round = round,
        name = "Round $round",
        country = "",
        locality = "",
        circuitName = "",
        countryCode = null,
        sessions = sessions.toList(),
    )

    private val calendar = listOf(
        weekend(
            23,
            ScheduledSession(SessionKind.QUALIFYING, "Qualifying", start - day),
            ScheduledSession(SessionKind.RACE, "Race", start),
        ),
        weekend(
            24,
            ScheduledSession(SessionKind.PRACTICE1, "Practice 1", null),
            ScheduledSession(SessionKind.SPRINT, "Sprint", start + 6 * day),
            ScheduledSession(SessionKind.RACE, "Race", start + 7 * day),
        ),
    )

    private val raceEnd = start + 2 * hour
    private val sprintEnd = start + 6 * day + hour
    private val finalEnd = start + 7 * day + 2 * hour

    private fun driver(
        position: Int,
        code: String,
        first: String,
        last: String,
        team: String,
        teamId: String,
        points: Double,
        wins: Int = 0,
    ) = DriverStanding(position, points, wins, code, null, first, last, "", team, teamId)

    // ------------------------------------------------------------------ labels

    @Test
    fun `drivers are listed by position with every label the layouts use`() {
        val entries = StandingsWidgetPlanner.driverEntries(
            listOf(
                driver(2, "NOR", "Lando", "Norris", "McLaren", "mclaren", 302.0, wins = 6),
                driver(1, "PIA", "Oscar", "Piastri", "McLaren", "mclaren", 324.0, wins = 7),
            ),
        )
        assertEquals(listOf("PIA", "NOR"), entries.map { it.code })
        assertEquals(
            StandingsEntry(
                position = 1,
                name = "O. Piastri",
                lastName = "Piastri",
                code = "PIA",
                team = "McLaren",
                points = 324.0,
                wins = 7,
                colorHex = "F47600",
            ),
            entries.first(),
        )
    }

    @Test
    fun `a driver without a code gets one from the last name, and the reverse`() {
        val entries = StandingsWidgetPlanner.driverEntries(
            listOf(
                driver(1, "", "Oliver", "Bearman", "Haas F1 Team", "haas", 41.0),
                driver(2, "XYZ", "", "", "Haas F1 Team", "haas", 0.0),
            ),
        )
        assertEquals("BEA", entries[0].code)
        assertEquals("Haas", entries[0].team)
        assertEquals("XYZ", entries[1].lastName)
    }

    @Test
    fun `teams are listed by position under their short name`() {
        val entries = StandingsWidgetPlanner.teamEntries(
            listOf(
                ConstructorStanding(2, 470.0, 3, "Ferrari", "ferrari", "Italian"),
                ConstructorStanding(10, 41.0, 0, "Haas F1 Team", "haas", "American"),
                ConstructorStanding(1, 626.0, 13, "McLaren", "mclaren", "British"),
            ),
        )
        assertEquals(listOf("McLaren", "Ferrari", "Haas"), entries.map { it.name })
        val haas = entries.last()
        assertEquals(listOf("Haas", "Haas", "Haas"), listOf(haas.lastName, haas.code, haas.team))
        assertEquals(13, entries.first().wins)
        assertEquals("F47600", entries.first().colorHex)
    }

    @Test
    fun `team names lose the F1 Team suffix only`() {
        assertEquals("Haas", StandingsWidgetPlanner.teamName("Haas F1 Team"))
        assertEquals("Red Bull", StandingsWidgetPlanner.teamName("Red Bull"))
    }

    @Test
    fun `points drop a zero decimal but keep a half point`() {
        assertEquals("242", StandingsWidgetPlanner.formatPoints(242.0))
        assertEquals("18.5", StandingsWidgetPlanner.formatPoints(18.5))
        assertEquals("0", StandingsWidgetPlanner.formatPoints(0.0))
    }

    @Test
    fun `gaps behind the leader carry a real minus sign`() {
        assertEquals("−22", StandingsWidgetPlanner.gapText(324.0, 302.0))
        assertEquals("−0.5", StandingsWidgetPlanner.gapText(300.0, 299.5))
        assertEquals("0", StandingsWidgetPlanner.gapText(324.0, 324.0))
    }

    // ------------------------------------------------------------------ rows

    @Test
    fun `the line goes after the last contender when a row fits under it`() {
        assertEquals(TablePlan(rows = 9, lineAfter = 3), tablePlan(200f, 20f, 16f, entries = 20, contenders = 3))
        assertEquals(TablePlan(rows = 9, lineAfter = 1), tablePlan(200f, 20f, 16f, entries = 20, contenders = 1))
    }

    @Test
    fun `no line when every row shown can still win`() {
        assertEquals(TablePlan(rows = 5, lineAfter = null), tablePlan(100f, 20f, 16f, entries = 20, contenders = 5))
        assertEquals(TablePlan(rows = 5, lineAfter = null), tablePlan(100f, 20f, 16f, entries = 20, contenders = 12))
    }

    @Test
    fun `a line with no row under it is left out, and so is the row it would cost`() {
        assertEquals(TablePlan(rows = 4, lineAfter = null), tablePlan(100f, 20f, 16f, entries = 20, contenders = 4))
    }

    @Test
    fun `without a title fight the table just fills the height`() {
        assertEquals(TablePlan(rows = 10, lineAfter = null), tablePlan(200f, 20f, 16f, entries = 20, contenders = null))
        assertEquals(TablePlan(rows = 10, lineAfter = null), tablePlan(200f, 20f, 16f, entries = 20, contenders = 0))
    }

    @Test
    fun `rows stop at the end of the table and at the cap`() {
        assertEquals(TablePlan(rows = 10, lineAfter = 1), tablePlan(400f, 20f, 16f, entries = 10, contenders = 1))
        assertEquals(TablePlan(rows = 20, lineAfter = null), tablePlan(1000f, 20f, 16f, entries = 22, contenders = null))
        assertEquals(TablePlan(rows = 8, lineAfter = 2), tablePlan(1000f, 20f, 16f, entries = 21, contenders = 2, maxRows = 8))
    }

    @Test
    fun `no rows when not even one fits`() {
        assertEquals(TablePlan(rows = 0, lineAfter = null), tablePlan(15f, 20f, 16f, entries = 20, contenders = 3))
        assertEquals(TablePlan(rows = 0, lineAfter = null), tablePlan(-4f, 20f, 16f, entries = 20, contenders = 3))
        assertEquals(TablePlan(rows = 0, lineAfter = null), tablePlan(200f, 20f, 16f, entries = 0, contenders = 3))
    }

    // ------------------------------------------------------------------ refresh

    @Test
    fun `races and sprints count, their end as the session widget times it`() {
        assertEquals(listOf(raceEnd, sprintEnd, finalEnd), StandingsWidgetPlanner.scoringSessionEnds(calendar))
    }

    @Test
    fun `a table never fetched, or fetched in the future, is due`() {
        assertTrue(StandingsWidgetPlanner.isDue(0L, calendar, start))
        assertTrue(StandingsWidgetPlanner.isDue(start + hour, calendar, start))
    }

    @Test
    fun `the table is due an hour after the race, and again at each later check`() {
        val beforeRace = start - 2 * hour
        assertFalse(StandingsWidgetPlanner.isDue(beforeRace, calendar, raceEnd + 30 * minute))
        assertTrue(StandingsWidgetPlanner.isDue(beforeRace, calendar, raceEnd + hour))

        val afterFirstCheck = raceEnd + hour + 5 * minute
        assertFalse(StandingsWidgetPlanner.isDue(afterFirstCheck, calendar, raceEnd + 2 * hour))
        assertTrue(StandingsWidgetPlanner.isDue(afterFirstCheck, calendar, raceEnd + 3 * hour))
    }

    @Test
    fun `between race weekends the table is due once a day`() {
        val fetched = start + 3 * day
        assertFalse(StandingsWidgetPlanner.isDue(fetched, calendar, fetched + 23 * hour))
        assertTrue(StandingsWidgetPlanner.isDue(fetched, calendar, fetched + day))
    }

    @Test
    fun `the next check is the next one after a race or a day after the fetch`() {
        val fetched = raceEnd + hour + 5 * minute
        assertEquals(raceEnd + 3 * hour, StandingsWidgetPlanner.nextCheckAt(fetched, calendar, fetched))
        val midweek = start + 3 * day
        assertEquals(midweek + day, StandingsWidgetPlanner.nextCheckAt(midweek, calendar, midweek + hour))
        val friday = start + 5 * day + 12 * hour
        assertEquals(sprintEnd + hour, StandingsWidgetPlanner.nextCheckAt(friday, calendar, friday))
        val december = finalEnd + 25 * hour
        assertEquals(december + day, StandingsWidgetPlanner.nextCheckAt(december, calendar, december))
    }

    @Test
    fun `checks are never closer than half an hour`() {
        assertEquals(start + 30 * minute, StandingsWidgetPlanner.nextCheckAt(0L, calendar, start))
        val now = raceEnd + 50 * minute
        assertEquals(now + 30 * minute, StandingsWidgetPlanner.nextCheckAt(now, calendar, now))
        val stale = start - day - hour
        val later = raceEnd + 5 * hour
        assertEquals(later + 30 * minute, StandingsWidgetPlanner.nextCheckAt(stale, calendar, later))
    }

    @Test
    fun `last season's table is fetched again only until the checks after its final race`() {
        assertTrue(StandingsWidgetPlanner.missesResults(0L, calendar))
        assertTrue(StandingsWidgetPlanner.missesResults(finalEnd + 12 * hour + minute, calendar))
        assertFalse(StandingsWidgetPlanner.missesResults(finalEnd + day, calendar))
        assertFalse(StandingsWidgetPlanner.missesResults(finalEnd + 40 * day, calendar))
    }
}
