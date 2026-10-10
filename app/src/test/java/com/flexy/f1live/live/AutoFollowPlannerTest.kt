package com.flexy.f1live.live

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFollowPlannerTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    private val weekend = RaceWeekend(
        season = 2026,
        round = 16,
        name = "Italian Grand Prix",
        country = "Italy",
        locality = "Monza",
        circuitName = "Autodromo Nazionale Monza",
        countryCode = "it",
        sessions = listOf(
            ScheduledSession(SessionKind.RACE, "Race", BASE + 48 * hour),
            ScheduledSession(SessionKind.PRACTICE1, "Practice 1", BASE),
            ScheduledSession(SessionKind.PRACTICE2, "Practice 2", BASE + 4 * hour),
            ScheduledSession(SessionKind.PRACTICE3, "Practice 3", null),
        ),
    )

    private val targets = AutoFollowPlanner.targets(listOf(weekend))

    @Test
    fun targetsSkipUnknownTimesSortAndLabel() {
        assertEquals(
            listOf(SessionKind.PRACTICE1, SessionKind.PRACTICE2, SessionKind.RACE),
            targets.map { it.kind },
        )
        assertEquals("Italian Grand Prix · Practice 1", targets.first().label)
    }

    @Test
    fun nextAlarmFiresLeadMinutesBeforeTheNextSession() {
        val alarm = AutoFollowPlanner.nextAlarm(targets, now = BASE - 2 * hour)!!
        assertEquals(SessionKind.PRACTICE1, alarm.target.kind)
        assertEquals(BASE - AutoFollowPlanner.LEAD_MS, alarm.triggerAtMillis)
    }

    @Test
    fun nextAlarmNeverRepicksTheSessionItJustFiredFor() {
        val firedAt = BASE - AutoFollowPlanner.LEAD_MS
        assertEquals(SessionKind.PRACTICE2, AutoFollowPlanner.nextAlarm(targets, firedAt)!!.target.kind)
        assertEquals(
            SessionKind.PRACTICE2,
            AutoFollowPlanner.nextAlarm(targets, firedAt + 30_000L)!!.target.kind,
        )
    }

    @Test
    fun nextAlarmIsNullAfterTheLastSession() {
        assertNull(AutoFollowPlanner.nextAlarm(targets, now = BASE + 49 * hour))
        assertNull(AutoFollowPlanner.nextAlarm(emptyList(), now = BASE))
    }

    @Test
    fun currentWindowCoversLeadThroughExpectedDuration() {
        assertNull(AutoFollowPlanner.currentWindow(targets, BASE - AutoFollowPlanner.LEAD_MS - 1))
        assertEquals(
            SessionKind.PRACTICE1,
            AutoFollowPlanner.currentWindow(targets, BASE - AutoFollowPlanner.LEAD_MS)?.kind,
        )
        assertEquals(SessionKind.PRACTICE1, AutoFollowPlanner.currentWindow(targets, BASE + hour)?.kind)
        assertNull(AutoFollowPlanner.currentWindow(targets, BASE + 2 * hour))
        assertEquals(
            SessionKind.RACE,
            AutoFollowPlanner.currentWindow(targets, BASE + 50 * hour + 30 * minute)?.kind,
        )
    }

    @Test
    fun waitDeadlineIsRelativeToStartButNeverShorterThanTheProbe() {
        val fp1 = targets.first()
        assertEquals(
            BASE + AutoFollowPlanner.WAIT_AFTER_START_MS,
            AutoFollowPlanner.waitDeadline(fp1, now = BASE - AutoFollowPlanner.LEAD_MS),
        )
        val late = BASE + hour
        assertEquals(late + AutoFollowPlanner.PROBE_MS, AutoFollowPlanner.waitDeadline(fp1, late))
        assertEquals(late + AutoFollowPlanner.PROBE_MS, AutoFollowPlanner.waitDeadline(null, late))
    }

    @Test
    fun dismissalCoversTheSessionButNotTheNextOne() {
        val fp1 = targets.first()
        val until = AutoFollowPlanner.suppressUntil(fp1, now = BASE + 10 * minute)
        assertEquals(AutoFollowPlanner.windowEnd(fp1), until)
        val fp2 = targets[1]
        assertTrue(fp2.startUtcMillis > until)
        assertEquals(
            BASE + AutoFollowPlanner.DEFAULT_SUPPRESS_MS,
            AutoFollowPlanner.suppressUntil(null, now = BASE),
        )
    }

    private companion object {
        const val BASE = 1_788_521_400_000L
    }
}
