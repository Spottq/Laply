package com.flexy.f1live.widget.lock

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.ui.components.CountdownUnits
import com.flexy.f1live.widget.Countdown
import com.flexy.f1live.widget.WidgetPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 2x1 lock-screen widget shows the running session, else the next one with its countdown. */
class LockWidgetPlanTest {

    private val hour = WidgetPlanner.HOUR_MS
    private val base = 1_757_676_600_000L // Fri 12 Sep 2025 11:30 UTC

    private val baku = RaceWeekend(
        season = 2025,
        round = 17,
        name = "Azerbaijan Grand Prix",
        country = "Azerbaijan",
        locality = "Baku",
        circuitName = "Baku City Circuit",
        countryCode = "az",
        sessions = listOf(
            ScheduledSession(SessionKind.PRACTICE1, "Practice 1", base + 2 * hour),
            ScheduledSession(SessionKind.PRACTICE2, "Practice 2", base + 6 * hour),
            ScheduledSession(SessionKind.RACE, "Race", base + 50 * hour),
        ),
    )

    private fun plan(now: Long) =
        LockWidgetPlan.of(WidgetPlanner.entries(listOf(baku), now, LockWidgetPlan.ENTRIES), now)

    @Test
    fun emptyCalendarIsEmpty() {
        assertEquals(LockWidgetPlan.Empty, LockWidgetPlan.of(emptyList(), base))
    }

    @Test
    fun underADayIsHoursAndMinutes() {
        val plan = plan(base) as LockWidgetPlan.Upcoming
        assertEquals(SessionKind.PRACTICE1, plan.entry.session.kind)
        assertEquals(Countdown.Until(CountdownUnits.HoursMinutes(2, 0)), plan.countdown)
    }

    @Test
    fun runningSessionIsLive() {
        val plan = plan(base + 2 * hour + 10 * 60_000L)
        assertTrue(plan is LockWidgetPlan.Live)
        assertEquals(SessionKind.PRACTICE1, (plan as LockWidgetPlan.Live).entry.session.kind)
    }

    @Test
    fun aDayOrMoreOutIsDaysHours() {
        // After FP2 ends: the race is 43 h away.
        val plan = plan(base + 7 * hour) as LockWidgetPlan.Upcoming
        assertEquals(SessionKind.RACE, plan.entry.session.kind)
        assertEquals(Countdown.Until(CountdownUnits.DaysHours(1, 19)), plan.countdown)
    }

    @Test
    fun sessionLabels() {
        assertEquals(LockSessionLabel.FP1, lockSessionLabel(SessionKind.PRACTICE1))
        assertEquals(LockSessionLabel.SQ, lockSessionLabel(SessionKind.SPRINT_QUALIFYING))
        assertEquals(LockSessionLabel.QUALI, lockSessionLabel(SessionKind.QUALIFYING))
        assertNull(lockSessionLabel(SessionKind.UNKNOWN))
    }
}
