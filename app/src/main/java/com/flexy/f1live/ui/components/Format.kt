package com.flexy.f1live.ui.components

import android.content.res.Resources
import com.flexy.f1live.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val zone: ZoneId get() = ZoneId.systemDefault()

private val dayTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.getDefault())
private val shortDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val clockFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())

/** "Sun 6 Sep · 16:00" in the device time zone. */
fun formatDayTime(utcMillis: Long?): String =
    utcMillis?.let { dayTimeFormatter.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "TBC"

/** "Sun 6 Sep" in the device time zone. */
fun formatDate(utcMillis: Long?): String =
    utcMillis?.let { shortDateFormatter.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "Date TBC"

/** "16:00" in the device time zone. */
fun formatTime(utcMillis: Long?): String =
    utcMillis?.let { timeFormatter.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "--:--"

private val dayMonthFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val dayOfMonthFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d", Locale.getDefault())

/**
 * "4 – 6 Sep", or "29 Aug – 1 Sep" across a month boundary, in the device time zone. A single
 * known end falls back to [formatDate].
 */
fun formatDateRange(startUtcMillis: Long?, endUtcMillis: Long?): String {
    if (startUtcMillis == null || endUtcMillis == null) return formatDate(startUtcMillis ?: endUtcMillis)
    val start = Instant.ofEpochMilli(startUtcMillis).atZone(zone).toLocalDate()
    val end = Instant.ofEpochMilli(endUtcMillis).atZone(zone).toLocalDate()
    return when {
        start == end -> dayMonthFormatter.format(start)
        start.year == end.year && start.month == end.month ->
            dayOfMonthFormatter.format(start) + " – " + dayMonthFormatter.format(end)
        else -> dayMonthFormatter.format(start) + " – " + dayMonthFormatter.format(end)
    }
}

/** "16:04:12" in the device time zone, for the race-control log. */
fun formatClock(utcMillis: Long?): String =
    utcMillis?.let { clockFormatter.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: ""

// ---------------------------------------------------------------- countdown

/**
 * A countdown in the whole units the app shows: days and hours from a day out, hours and minutes
 * under a day, then minutes (never "0 min"); [Now] once the start has passed. Shared by the Live
 * tab's next-round card and the widgets, so they always word it the same.
 */
sealed interface CountdownUnits {
    data class DaysHours(val days: Int, val hours: Int) : CountdownUnits
    data class HoursMinutes(val hours: Int, val minutes: Int) : CountdownUnits
    data class Minutes(val minutes: Int) : CountdownUnits
    data object Now : CountdownUnits
}

private const val COUNTDOWN_MINUTE_MS = 60_000L

fun countdownUnits(remainingMillis: Long): CountdownUnits {
    if (remainingMillis <= 0L) return CountdownUnits.Now
    val totalMinutes = remainingMillis / COUNTDOWN_MINUTE_MS
    val days = (totalMinutes / (24 * 60)).toInt()
    val hours = ((totalMinutes / 60) % 24).toInt()
    val minutes = (totalMinutes % 60).toInt()
    return when {
        days > 0 -> CountdownUnits.DaysHours(days, hours)
        hours > 0 -> CountdownUnits.HoursMinutes(hours, minutes)
        else -> CountdownUnits.Minutes(minutes.coerceAtLeast(1))
    }
}

/** "in 2d 14h", "in 3h 12m", "in 8 min"; "Starting now" once the start time has passed. */
fun formatCountdown(resources: Resources, units: CountdownUnits): String = when (units) {
    is CountdownUnits.DaysHours -> resources.getString(R.string.countdown_days_hours, units.days, units.hours)
    is CountdownUnits.HoursMinutes -> resources.getString(R.string.countdown_hours_minutes, units.hours, units.minutes)
    is CountdownUnits.Minutes -> resources.getString(R.string.countdown_minutes, units.minutes)
    CountdownUnits.Now -> resources.getString(R.string.countdown_now)
}

fun formatCountdown(resources: Resources, remainingMillis: Long): String =
    formatCountdown(resources, countdownUnits(remainingMillis))
