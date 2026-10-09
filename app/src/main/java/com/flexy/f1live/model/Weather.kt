package com.flexy.f1live.model

import androidx.compose.runtime.Immutable
import java.time.LocalDate

@Immutable
data class DayForecast(
    val date: LocalDate,
    val weatherCode: Int?,
    val maxTempC: Double?,
    val minTempC: Double?,
    val rainChancePct: Int?,
)

@Immutable
data class SessionForecast(
    val weatherCode: Int?,
    val tempC: Double?,
    val rainChancePct: Int?,
    val isDay: Boolean = true,
)

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
    val days: List<DayForecast>,
    val sessions: Map<SessionKind, SessionForecast>,
    val hours: List<HourForecast> = emptyList(),
) {
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

@Immutable
sealed interface WeekendWeather {
    data object Loading : WeekendWeather

    data class Ready(val forecast: WeekendForecast) : WeekendWeather

    data class TooEarly(val fromUtcMillis: Long) : WeekendWeather

    data object Unavailable : WeekendWeather
}
