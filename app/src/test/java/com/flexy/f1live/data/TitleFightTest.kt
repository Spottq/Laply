package com.flexy.f1live.data

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleFightTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_790_000_000_000L

    private fun weekend(round: Int, raceStart: Long?, sprint: Boolean = false) = RaceWeekend(
        season = 2026,
        round = round,
        name = "Round $round",
        country = "",
        locality = "",
        circuitName = "",
        countryCode = null,
        sessions = listOfNotNull(
            ScheduledSession(SessionKind.PRACTICE1, "Practice 1", raceStart?.minus(2 * day)),
            if (sprint) ScheduledSession(SessionKind.SPRINT_QUALIFYING, "Sprint Qualifying", raceStart?.minus(2 * day)) else null,
            if (sprint) ScheduledSession(SessionKind.SPRINT, "Sprint", raceStart?.minus(day)) else null,
            ScheduledSession(SessionKind.QUALIFYING, "Qualifying", raceStart?.minus(day)),
            ScheduledSession(SessionKind.RACE, "Race", raceStart),
        ),
    )

    /** 24 rounds a week apart, round 20 the most recent; round 22 has a sprint. */
    private val calendar = (1..24).map { round ->
        weekend(round, raceStart = now + (round - 20) * 7 * day - day, sprint = round == 22)
    }

    private fun driver(position: Int, points: Double) = DriverStanding(
        position = position,
        points = points,
        wins = 0,
        code = "D$position",
        number = null,
        firstName = "",
        lastName = "Driver $position",
        nationality = "",
        constructorName = "",
        constructorId = "",
    )

    private val table = listOf(driver(1, 324.0), driver(2, 302.0), driver(3, 241.0), driver(4, 198.0))

    @Test
    fun `counts the races and sprints after the standings round`() {
        val fight = TitleFight.of(table, afterRound = 20, weekends = calendar, nowUtcMillis = now)!!
        assertEquals(4, fight.racesLeft)
        assertEquals(1, fight.sprintsLeft)
        assertEquals(108, fight.pointsLeft)
        assertEquals("4 races + 1 sprint left · 108 pts to play for", fight.summary)
    }

    @Test
    fun `drivers who cannot reach the leader any more fall below the line`() {
        val fight = TitleFight.of(table, afterRound = 20, weekends = calendar, nowUtcMillis = now)!!
        // 241 + 108 still reaches 324; 198 + 108 does not.
        assertEquals(3, fight.contenders)
        assertFalse(fight.decided)
    }

    @Test
    fun `a driver who can only draw level is still in the fight`() {
        val level = listOf(driver(1, 300.0), driver(2, 192.0), driver(3, 191.0))
        val fight = TitleFight.of(level, afterRound = 20, weekends = calendar, nowUtcMillis = now)!!
        assertEquals(2, fight.contenders)
    }

    @Test
    fun `a leader nobody can catch has the title before the last race`() {
        val runaway = listOf(driver(1, 400.0), driver(2, 250.0))
        val fight = TitleFight.of(runaway, afterRound = 20, weekends = calendar, nowUtcMillis = now)!!
        assertEquals(1, fight.contenders)
        assertTrue(fight.decided)
        assertEquals(108, fight.pointsLeft)
    }

    @Test
    fun `after the last round the leader is champion even on a tie`() {
        val tie = listOf(driver(1, 400.0), driver(2, 400.0))
        val fight = TitleFight.of(tie, afterRound = 24, weekends = calendar, nowUtcMillis = now + 30 * day)!!
        assertEquals(0, fight.pointsLeft)
        assertEquals(1, fight.contenders)
        assertNull(fight.summary)
    }

    @Test
    fun `one race left reads in the singular`() {
        val fight = TitleFight.of(table, afterRound = 23, weekends = calendar, nowUtcMillis = now + 21 * day)!!
        assertEquals("1 race left · 25 pts to play for", fight.summary)
    }

    @Test
    fun `standings after a sprint still leave that weekend's race to come`() {
        // Round 22 is the sprint weekend; its race starts 13 days from now.
        val beforeRace = TitleFight.of(table, afterRound = 22, weekends = calendar, nowUtcMillis = now + 12 * day)!!
        assertEquals(3, beforeRace.racesLeft)
        assertEquals(0, beforeRace.sprintsLeft)

        // Published after the race they carry the same round number, so the race keeps counting
        // for a day: an extra driver above the line for a few hours beats one dropped too early.
        val rightAfter = TitleFight.of(table, afterRound = 22, weekends = calendar, nowUtcMillis = now + 13 * day + 3_600_000L)!!
        assertEquals(3, rightAfter.racesLeft)

        val nextDay = TitleFight.of(table, afterRound = 22, weekends = calendar, nowUtcMillis = now + 15 * day)!!
        assertEquals(2, nextDay.racesLeft)
    }

    @Test
    fun `an ordinary weekend's race is in its standings`() {
        val fight = TitleFight.of(table, afterRound = 21, weekends = calendar, nowUtcMillis = now + 6 * day)!!
        assertEquals(3, fight.racesLeft)
        assertEquals(1, fight.sprintsLeft)
    }

    @Test
    fun `a race without a time yet still counts`() {
        val weekends = calendar.map { if (it.round == 24) weekend(24, raceStart = null) else it }
        val fight = TitleFight.of(table, afterRound = 20, weekends = weekends, nowUtcMillis = now)!!
        assertEquals(4, fight.racesLeft)
    }

    @Test
    fun `nothing to go on without standings or with a calendar that stops short`() {
        assertNull(TitleFight.of(emptyList(), afterRound = 0, weekends = calendar, nowUtcMillis = now))
        assertNull(TitleFight.of(table, afterRound = 20, weekends = emptyList(), nowUtcMillis = now))
        assertNull(TitleFight.of(table, afterRound = 20, weekends = calendar.take(12), nowUtcMillis = now))
    }

    @Test
    fun `the table order decides the line, not the order it arrives in`() {
        val shuffled = listOf(driver(4, 198.0), driver(1, 324.0), driver(3, 241.0), driver(2, 302.0))
        val fight = TitleFight.of(shuffled, afterRound = 20, weekends = calendar, nowUtcMillis = now)!!
        assertEquals(3, fight.contenders)
    }
}
