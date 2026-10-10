package com.flexy.f1live.widget

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.ui.components.CountdownUnits
import com.flexy.f1live.ui.components.countdownUnits
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class WidgetEntry(
    val weekend: RaceWeekend,
    val session: ScheduledSession,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
) {
    fun isLive(now: Long): Boolean = now >= startUtcMillis && now < endUtcMillis
}

sealed interface Countdown {
    data object Live : Countdown

    data class Until(val units: CountdownUnits) : Countdown
}

enum class DayLabel { TODAY, TOMORROW, THIS_WEEK, LATER }

object WidgetPlanner {

    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60L * MINUTE_MS
    const val DAY_MS = 24L * HOUR_MS

    const val DAYS_THRESHOLD_MS = DAY_MS

    const val MAX_REFRESH_INTERVAL_MS = 6L * HOUR_MS

    const val MAX_ENTRIES = 12

    fun durationMs(kind: SessionKind): Long = when (kind) {
        SessionKind.PRACTICE1, SessionKind.PRACTICE2, SessionKind.PRACTICE3 -> 60L
        SessionKind.QUALIFYING -> 60L
        SessionKind.SPRINT_QUALIFYING -> 45L
        SessionKind.SPRINT -> 60L
        SessionKind.RACE -> 120L
        SessionKind.UNKNOWN -> 60L
    } * MINUTE_MS

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

    fun countdownTarget(entries: List<WidgetEntry>, now: Long): WidgetEntry? =
        entries.firstOrNull { !it.isLive(now) }

    fun countdown(entry: WidgetEntry, now: Long): Countdown =
        if (entry.isLive(now)) Countdown.Live else Countdown.Until(countdownUnits(entry.startUtcMillis - now))

    fun nextRefreshAt(entries: List<WidgetEntry>, now: Long, zone: ZoneId): Long {
        val candidates = ArrayList<Long>(entries.size + 3)
        for (entry in entries) {
            candidates += if (entry.isLive(now)) entry.endUtcMillis else entry.startUtcMillis
        }
        countdownTarget(entries, now)?.let { target ->
            val remaining = target.startUtcMillis - now
            if (remaining > 0) {
                val unit = if (remaining >= DAYS_THRESHOLD_MS) HOUR_MS else MINUTE_MS
                candidates += target.startUtcMillis - (remaining / unit) * unit + 1
            }
        }
        candidates += nextMidnight(now, zone)
        candidates += now + MAX_REFRESH_INTERVAL_MS
        return candidates.filter { it > now }.min()
    }

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

    fun rowsThatFit(space: Float, rowHeight: Float, gap: Float): Int =
        if (rowHeight <= 0f || space < rowHeight) 0 else ((space + gap) / (rowHeight + gap)).toInt()

    fun shortName(name: String): String = name.replace("Grand Prix", "GP").trim()

    fun weekendUnderway(weekend: RaceWeekend, now: Long): Boolean =
        weekend.sessions.any { (it.startUtcMillis ?: Long.MAX_VALUE) <= now }
}
