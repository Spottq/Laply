package com.flexy.f1live.widget.lock

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.widget.WidgetPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 2x2 lock-screen widget lists the sessions after its headline, as many as fit. */
class LockWidgetLargePlanTest {

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
            ScheduledSession(SessionKind.QUALIFYING, "Qualifying", base + 30 * hour),
            ScheduledSession(SessionKind.RACE, "Race", base + 50 * hour),
        ),
    )

    private fun entries(now: Long) = WidgetPlanner.entries(listOf(baku), now, LockWidgetLargePlan.ENTRIES)

    @Test
    fun rowsFollowTheHeadline() {
        val entries = entries(base)
        assertEquals(LockWidgetLargePlan.ENTRIES, entries.size)
        val rows = LockWidgetLargePlan.rows(entries, 2)
        assertEquals(listOf(SessionKind.PRACTICE2, SessionKind.QUALIFYING), rows.map { it.session.kind })
    }

    @Test
    fun liveHeadlineRowsAreWhatComesNext() {
        val now = base + 2 * hour + 10 * 60_000L // FP1 running
        val entries = entries(now)
        assertTrue(LockWidgetPlan.of(entries, now) is LockWidgetPlan.Live)
        assertEquals(SessionKind.PRACTICE2, LockWidgetLargePlan.rows(entries, 1).single().session.kind)
    }

    @Test
    fun rowsNeverExceedWhatIsLeft() {
        val now = base + 31 * hour // only the race left
        assertEquals(emptyList<Any>(), LockWidgetLargePlan.rows(entries(now), 2))
        assertEquals(emptyList<Any>(), LockWidgetLargePlan.rows(emptyList(), 2))
    }

    @Test
    fun samsungSlotFitsTwoRowsSmallerFitsFewer() {
        assertEquals(2, LockWidgetLargePlan.rowsThatFit(LockWidgetLargePlan.DEFAULT_HEIGHT_DP))
        assertEquals(1, LockWidgetLargePlan.rowsThatFit(124))
        assertEquals(0, LockWidgetLargePlan.rowsThatFit(100))
        assertEquals(LockWidgetLargePlan.MAX_ROWS, LockWidgetLargePlan.rowsThatFit(400))
    }

    @Test
    fun largerFontShowsFewerRows() {
        assertTrue(
            LockWidgetLargePlan.rowsThatFit(133, fontScale = 1.3f) <
                LockWidgetLargePlan.rowsThatFit(133, fontScale = 1f),
        )
    }
}
