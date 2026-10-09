package com.flexy.f1live.model

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/** One day of a race weekend, in the circuit's own calendar (Friday at Austin is Friday there). */
@Immutable
data class DayForecast(
    val date: LocalDate,
    /** WMO weather interpretation code (0 clear ... 99 thunderstorm with hail); null if unknown. */
    val weatherCode: Int?,
    val maxTempC: Double?,
    val minTempC: Double?,
    /** Highest hourly chance of precipitation that day, 0-100. */
    val rainChancePct: Int?,
)

/** The hour a session starts in. */
@Immutable
data class SessionForecast(
    val weatherCode: Int?,
    val tempC: Double?,
    val rainChancePct: Int?,
    /** False after sunset, so a night race shows the moon. */
    val isDay: Boolean = true,
)

/** One hour of the weekend, starting at [utcMillis]. */
@Immutable
data class HourForecast(
    val utcMillis: Long,
    val weatherCode: Int?,
    val tempC: Double?,
    val rainChancePct: Int?,
    val isDay: Boolean = true,
)

@Immutable
data class WeekendForecast(
    /** The weekend's days the forecast already reaches, oldest first; never empty. */
    val days: List<DayForecast>,
    /** Sessions the forecast reaches, by kind; a session too far out is missing. */
    val sessions: Map<SessionKind, SessionForecast>,
    /** Hour by hour around the sessions, oldest first. */
    val hours: List<HourForecast> = emptyList(),
) {
    /**
     * The hours around a session: [count] of them from two hours before its start, rounded up to
     * the hour - a 11:30 session gets 10:00, 11:00, 12:00 and 13:00. Fewer near the horizon.
     */
    fun hoursAround(startUtcMillis: Long, count: Int = 4): List<HourForecast> {
        val from = ceilToHour(startUtcMillis - HOURS_BEFORE * HOUR_MILLIS)
        return hours.dropWhile { it.utcMillis < from }.take(count)
    }

    companion object {
        const val HOUR_MILLIS = 60L * 60 * 1000
        const val HOURS_BEFORE = 2L

        fun ceilToHour(utcMillis: Long): Long = Math.floorDiv(utcMillis + HOUR_MILLIS - 1, HOUR_MILLIS) * HOUR_MILLIS
    }
}

/** What the next-round card can say about the weekend's weather. */
@Immutable
sealed interface WeekendWeather {
    data object Loading : WeekendWeather

    data class Ready(val forecast: WeekendForecast) : WeekendWeather

    /** The weekend lies beyond the forecast horizon; it comes into range at [fromUtcMillis]. */
    data class TooEarly(val fromUtcMillis: Long) : WeekendWeather

    /** No coordinates for the circuit, or no weather service answered. */
    data object Unavailable : WeekendWeather
}
