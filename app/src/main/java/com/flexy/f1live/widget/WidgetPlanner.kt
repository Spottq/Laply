package com.flexy.f1live.widget

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.ui.components.CountdownUnits
import com.flexy.f1live.ui.components.countdownUnits
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * One session a widget can show: the weekend it belongs to, its scheduled start and the moment the
 * widget stops calling it "live" ([endUtcMillis], start + [WidgetPlanner.durationMs]).
 */
data class WidgetEntry(
    val weekend: RaceWeekend,
    val session: ScheduledSession,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
) {
    fun isLive(now: Long): Boolean = now >= startUtcMillis && now < endUtcMillis
}

/** What a widget shows for a session: LIVE while it runs, otherwise the app's countdown text. */
sealed interface Countdown {
    /** The session is running now. */
    data object Live : Countdown

    /** "in 1d 13h" / "in 16h 41m" / "in 8 min" / "Starting now", worded by formatCountdown. */
    data class Until(val units: CountdownUnits) : Countdown
}

/** How far away a session's day is, for "Today · 15:00" / "Sat · 15:00" / "Sat 4 Oct · 15:00". */
enum class DayLabel { TODAY, TOMORROW, THIS_WEEK, LATER }

/**
 * Pure (Android-free) rules behind the home-screen widgets, kept apart from the Glance and alarm
 * glue so they can be unit-tested: which sessions to list, when one counts as live, what the
 * countdown says, and when the widget must be redrawn next.
 *
 * The countdown reads like the app's ("in 1d 13h", "in 16h 41m", "in 8 min") and is redrawn
 * exactly when that text changes: on the hour while it shows days, on the minute under a day.
 * Beyond that the widget is redrawn only when its *content* changes: a session starts (becomes
 * LIVE), a live session ends (the list moves on), the date rolls over ("Tomorrow" -> "Today").
 */
object WidgetPlanner {

    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60L * MINUTE_MS
    const val DAY_MS = 24L * HOUR_MS

    /** From this far out the countdown shows days and hours; below it, hours and minutes. */
    const val DAYS_THRESHOLD_MS = DAY_MS

    /** Safety net: never go longer than this without a redraw, whatever the schedule says. */
    const val MAX_REFRESH_INTERVAL_MS = 6L * HOUR_MS

    /** Most sessions any layout lists (a 4x5 widget on a tablet). */
    const val MAX_ENTRIES = 12

    /**
     * How long a session is shown as LIVE after its scheduled start. Deliberately the nominal
     * length, not the generous auto-follow window: past this the widget moves on to what is next.
     */
    fun durationMs(kind: SessionKind): Long = when (kind) {
        SessionKind.PRACTICE1, SessionKind.PRACTICE2, SessionKind.PRACTICE3 -> 60L
        SessionKind.QUALIFYING -> 60L
        SessionKind.SPRINT_QUALIFYING -> 45L
        SessionKind.SPRINT -> 60L
        SessionKind.RACE -> 120L
        SessionKind.UNKNOWN -> 60L
    } * MINUTE_MS

    /**
     * The live session (if any) followed by the upcoming ones, in start order, at most [limit].
     * Sessions without a known start time are skipped: they can be neither counted down nor sorted.
     */
    fun entries(weekends: List<RaceWeekend>, now: Long, limit: Int = MAX_ENTRIES): List<WidgetEntry> =
        weekends
            .asSequence()
            .flatMap { weekend ->
                weekend.sessions.asSequence().mapNotNull { session ->
                    val start = session.startUtcMillis ?: return@mapNotNull null
                    WidgetEntry(weekend, session, start, start + durationMs(session.kind))
                }
            }
            .filter { it.endUtcMillis > now }
            .sortedBy { it.startUtcMillis }
            .take(limit)
            .toList()

    /** The first entry that has not started: the one that gets the countdown. */
    fun countdownTarget(entries: List<WidgetEntry>, now: Long): WidgetEntry? =
        entries.firstOrNull { !it.isLive(now) }

    /** The countdown for [entry] at [now]: LIVE while it runs, else the app's whole-unit text. */
    fun countdown(entry: WidgetEntry, now: Long): Countdown =
        if (entry.isLive(now)) Countdown.Live else Countdown.Until(countdownUnits(entry.startUtcMillis - now))

    /**
     * When the widget must be redrawn next, strictly after [now]:
     *  - a listed session starts (it turns LIVE) or a live one ends (the list moves on);
     *  - the countdown text changes: the next whole hour while it reads "in Xd Yh", the next
     *    whole minute under a day ("in Xh Ym", "in N min");
     *  - local midnight, for "Today" / "Tomorrow";
     *  - [MAX_REFRESH_INTERVAL_MS] at the latest.
     */
    fun nextRefreshAt(entries: List<WidgetEntry>, now: Long, zone: ZoneId): Long {
        val candidates = ArrayList<Long>(entries.size + 3)
        for (entry in entries) {
            candidates += if (entry.isLive(now)) entry.endUtcMillis else entry.startUtcMillis
        }
        countdownTarget(entries, now)?.let { target ->
            val remaining = target.startUtcMillis - now
            if (remaining > 0) {
                // The text shows whole units, rounded down; it changes the instant `remaining`
                // drops below the current whole number of hours (days out) or minutes.
                val unit = if (remaining >= DAYS_THRESHOLD_MS) HOUR_MS else MINUTE_MS
                candidates += target.startUtcMillis - (remaining / unit) * unit + 1
            }
        }
        candidates += nextMidnight(now, zone)
        candidates += now + MAX_REFRESH_INTERVAL_MS
        return candidates.filter { it > now }.min()
    }

    /** The first instant of tomorrow in [zone]. */
    fun nextMidnight(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayLabel(startUtcMillis: Long, now: Long, zone: ZoneId): DayLabel {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = Instant.ofEpochMilli(startUtcMillis).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, day)
        return when {
            days <= 0L -> DayLabel.TODAY
            days == 1L -> DayLabel.TOMORROW
            days < 7L -> DayLabel.THIS_WEEK
            else -> DayLabel.LATER
        }
    }

    /**
     * Whole list rows of [rowHeight], [gap] apart, that fit in [space] (all in dp): a row that
     * would not fit completely is not drawn at all rather than clipped.
     */
    fun rowsThatFit(space: Float, rowHeight: Float, gap: Float): Int =
        if (rowHeight <= 0f || space < rowHeight) 0 else ((space + gap) / (rowHeight + gap)).toInt()

    /** "Italian Grand Prix" -> "Italian GP", for the narrow layouts. */
    fun shortName(name: String): String = name.replace("Grand Prix", "GP").trim()

    /** True once any session of [weekend] has started: the card says "This weekend", not "Next round". */
    fun weekendUnderway(weekend: RaceWeekend, now: Long): Boolean =
        weekend.sessions.any { (it.startUtcMillis ?: Long.MAX_VALUE) <= now }
}
