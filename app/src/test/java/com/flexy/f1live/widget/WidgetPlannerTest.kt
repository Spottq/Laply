package com.flexy.f1live.widget

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.ui.components.CountdownUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The widgets list the running session and the next ones, count down in hour-accurate text over a
 * day out and with a Chronometer under a day, and must be redrawn exactly when what they show
 * changes - never every minute.
 */
class WidgetPlannerTest {

    private val minute = WidgetPlanner.MINUTE_MS
    private val hour = WidgetPlanner.HOUR_MS
    private val utc: ZoneId = ZoneOffset.UTC

    /** Friday 12 Sep 2025 11:30 UTC. */
    private val base = LocalDateTime.of(2025, 9, 12, 11, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun weekend(round: Int, name: String, vararg sessions: ScheduledSession) = RaceWeekend(
        season = 2025,
        round = round,
        name = name,
        country = "Italy",
        locality = "Monza",
        circuitName = "Autodromo Nazionale Monza",
        countryCode = "it",
        sessions = sessions.toList(),
    )

    private val monza = weekend(
        16, "Italian Grand Prix",
        // Out of order on purpose, plus one session without a time.
        ScheduledSession(SessionKind.RACE, "Race", base + 50 * hour),
        ScheduledSession(SessionKind.PRACTICE1, "Practice 1", base),
        ScheduledSession(SessionKind.PRACTICE2, "Practice 2", base + 4 * hour),
        ScheduledSession(SessionKind.PRACTICE3, "Practice 3", null),
        ScheduledSession(SessionKind.QUALIFYING, "Qualifying", base + 28 * hour),
    )
    private val baku = weekend(
        17, "Azerbaijan Grand Prix",
        ScheduledSession(SessionKind.PRACTICE1, "Practice 1", base + 7 * 24 * hour),
    )

    // ------------------------------------------------------------------ entries

    @Test
    fun entriesAreSortedSkipUnknownTimesAndSpanWeekends() {
        val entries = WidgetPlanner.entries(listOf(baku, monza), now = base - hour)
        assertEquals(
            listOf("Practice 1", "Practice 2", "Qualifying", "Race", "Practice 1"),
            entries.map { it.session.name },
        )
        assertEquals(17, entries.last().weekend.round)
    }

    @Test
    fun entriesKeepTheRunningSessionAndDropFinishedOnes() {
        // 30 min into FP1 (60 min long): FP1 is still listed, and live.
        val during = WidgetPlanner.entries(listOf(monza), now = base + 30 * minute)
        assertEquals("Practice 1", during.first().session.name)
        assertTrue(during.first().isLive(base + 30 * minute))

        // One minute after its nominal end FP1 is gone.
        val after = WidgetPlanner.entries(listOf(monza), now = base + 61 * minute)
        assertEquals("Practice 2", after.first().session.name)
        assertFalse(after.first().isLive(base + 61 * minute))
    }

    @Test
    fun entriesHonourTheLimit() {
        assertEquals(2, WidgetPlanner.entries(listOf(monza, baku), base - hour, limit = 2).size)
        assertTrue(WidgetPlanner.entries(emptyList(), base).isEmpty())
    }

    @Test
    fun raceIsLiveForTwoHoursPracticeForOne() {
        assertEquals(2 * hour, WidgetPlanner.durationMs(SessionKind.RACE))
        assertEquals(hour, WidgetPlanner.durationMs(SessionKind.PRACTICE2))
        val race = WidgetPlanner.entries(listOf(monza), base + 50 * hour + 90 * minute).first()
        assertEquals(SessionKind.RACE, race.session.kind)
        assertTrue(race.isLive(base + 50 * hour + 90 * minute))
        assertFalse(race.isLive(base + 52 * hour))
    }

    // ------------------------------------------------------------------ countdown

    @Test
    fun countdownTargetSkipsTheLiveSession() {
        val now = base + 10 * minute
        val entries = WidgetPlanner.entries(listOf(monza), now)
        assertEquals("Practice 2", WidgetPlanner.countdownTarget(entries, now)!!.session.name)
        assertEquals(Countdown.Live, WidgetPlanner.countdown(entries.first(), now))
    }

    @Test
    fun overADayIsDaysAndHoursRoundedDown() {
        val race = WidgetPlanner.entries(listOf(monza), base).first { it.session.kind == SessionKind.RACE }
        // 50 h out, minus 30 min: 49 h 30 m -> "2d 1h".
        assertEquals(Countdown.Until(CountdownUnits.DaysHours(2, 1)), WidgetPlanner.countdown(race, base + 30 * minute))
        // Exactly 24 h still shows days.
        assertEquals(Countdown.Until(CountdownUnits.DaysHours(1, 0)), WidgetPlanner.countdown(race, base + 26 * hour))
    }

    @Test
    fun underADayIsHoursAndMinutesThenMinutes() {
        val fp2 = WidgetPlanner.entries(listOf(monza), base + 2 * hour).first()
        val now = base + 2 * hour
        // "in 1h 59m", never a ticking clock with seconds.
        assertEquals(Countdown.Until(CountdownUnits.HoursMinutes(1, 59)), WidgetPlanner.countdown(fp2, now + 30_000))
        assertEquals(Countdown.Until(CountdownUnits.HoursMinutes(2, 0)), WidgetPlanner.countdown(fp2, now))
        assertEquals(
            Countdown.Until(CountdownUnits.Minutes(5)),
            WidgetPlanner.countdown(fp2, base + 4 * hour - 5 * minute),
        )
        // Under a minute still reads "1 min", never "0 min".
        assertEquals(
            Countdown.Until(CountdownUnits.Minutes(1)),
            WidgetPlanner.countdown(fp2, base + 4 * hour - 20_000),
        )
    }

    // ------------------------------------------------------------------ refresh timing

    @Test
    fun refreshOnTheNextHourBoundaryWhileTheTextShowsHours() {
        // Only the race left, 49 h 30 m out: "2d 1h" becomes "2d 0h" at exactly 49 h to go.
        val raceOnly = WidgetPlanner.entries(listOf(monza), base + hour)
            .filter { it.session.kind == SessionKind.RACE }
        val t = base + 30 * minute
        assertEquals(base + 50 * hour - 49 * hour + 1, WidgetPlanner.nextRefreshAt(raceOnly, t, utc))
    }

    @Test
    fun underADayRefreshOnTheNextMinute() {
        // FP2 at +4h, now 1 h 30 s in: "in 2h 59m" becomes "in 2h 58m" at exactly 2 h 59 m to go.
        val now = base + hour + 30_000
        val entries = WidgetPlanner.entries(listOf(monza), now)
        assertEquals("Practice 2", entries.first().session.name)
        assertEquals(base + 4 * hour - 179 * minute + 1, WidgetPlanner.nextRefreshAt(entries, now, utc))
        // Last minute: "in 1 min" until FP2 starts and turns LIVE.
        val lastMinute = base + 4 * hour - 20_000
        assertEquals(base + 4 * hour, WidgetPlanner.nextRefreshAt(entries, lastMinute, utc))
    }

    @Test
    fun liveSessionRefreshesAtItsEnd() {
        // The race running, nothing after it: the next redraw is its end.
        val now = base + 50 * hour + 10 * minute
        val entries = WidgetPlanner.entries(listOf(monza), now)
        assertEquals(base + 52 * hour, WidgetPlanner.nextRefreshAt(entries, now, utc))
    }

    @Test
    fun refreshAtMidnightAndNeverLaterThanTheSafetyNet() {
        // Nothing left: local midnight or the 6 h safety net, whichever is first.
        val lateEvening = LocalDateTime.of(2025, 12, 20, 22, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val midnight = LocalDateTime.of(2025, 12, 21, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(midnight, WidgetPlanner.nextRefreshAt(emptyList(), lateEvening, utc))
        val morning = LocalDateTime.of(2025, 12, 20, 8, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(
            morning + WidgetPlanner.MAX_REFRESH_INTERVAL_MS,
            WidgetPlanner.nextRefreshAt(emptyList(), morning, utc),
        )
    }

    @Test
    fun refreshIsAlwaysInTheFuture() {
        for (offset in listOf(-3 * hour, 0L, 59 * minute, 60 * minute, 26 * hour, 50 * hour, 200 * hour)) {
            val now = base + offset
            val entries = WidgetPlanner.entries(listOf(monza, baku), now)
            assertTrue("offset $offset", WidgetPlanner.nextRefreshAt(entries, now, utc) > now)
        }
    }

    // ------------------------------------------------------------------ labels

    @Test
    fun dayLabels() {
        // base is Friday 11:30 UTC.
        assertEquals(DayLabel.TODAY, WidgetPlanner.dayLabel(base + 4 * hour, base, utc))
        assertEquals(DayLabel.TOMORROW, WidgetPlanner.dayLabel(base + 20 * hour, base, utc))
        assertEquals(DayLabel.THIS_WEEK, WidgetPlanner.dayLabel(base + 50 * hour, base, utc))
        assertEquals(DayLabel.LATER, WidgetPlanner.dayLabel(base + 7 * 24 * hour, base, utc))
        // A live session that began before midnight still counts as today.
        assertEquals(DayLabel.TODAY, WidgetPlanner.dayLabel(base - 12 * hour, base, utc))
    }

    @Test
    fun rowsThatFitCountsWholeRowsOnly() {
        assertEquals(0, WidgetPlanner.rowsThatFit(45f, 46f, 3f))
        assertEquals(1, WidgetPlanner.rowsThatFit(46f, 46f, 3f))
        assertEquals(1, WidgetPlanner.rowsThatFit(94f, 46f, 3f))
        assertEquals(2, WidgetPlanner.rowsThatFit(95f, 46f, 3f))
        // A 4x3 One UI widget (~340 dp tall, 12 dp padding each side): six two-line rows.
        assertEquals(6, WidgetPlanner.rowsThatFit(316f, 46f, 3f))
        assertEquals(0, WidgetPlanner.rowsThatFit(100f, 0f, 3f))
    }

    @Test
    fun shortNameAndWeekendUnderway() {
        assertEquals("Italian GP", WidgetPlanner.shortName("Italian Grand Prix"))
        assertEquals("Emilia Romagna GP", WidgetPlanner.shortName("Emilia Romagna Grand Prix"))
        assertFalse(WidgetPlanner.weekendUnderway(monza, base - 1))
        assertTrue(WidgetPlanner.weekendUnderway(monza, base))
        assertNull(WidgetPlanner.countdownTarget(emptyList(), base))
    }
}
