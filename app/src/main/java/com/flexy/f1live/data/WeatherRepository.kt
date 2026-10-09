package com.flexy.f1live.data

import com.flexy.f1live.model.DayForecast
import com.flexy.f1live.model.HourForecast
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.SessionForecast
import com.flexy.f1live.model.WeekendForecast
import com.flexy.f1live.model.WeekendWeather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

class WeatherRepository(
    http: OkHttpClient,
    private val hosts: List<String> = DEFAULT_HOSTS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val client: OkHttpClient = http.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Pair<Long, WeekendForecast>>()

    suspend fun forecast(weekend: RaceWeekend): WeekendWeather {
        val lat = weekend.latitude
        val lon = weekend.longitude
        val starts = weekend.sessions.mapNotNull { it.startUtcMillis }
        if (lat == null || lon == null || starts.isEmpty()) return WeekendWeather.Unavailable

        val now = clock()
        val key = CircuitMaps.key(weekend)
        mutex.withLock { cache[key] }
            ?.takeIf { (at, _) -> now - at < FRESH_MILLIS }
            ?.let { (_, forecast) -> return WeekendWeather.Ready(forecast) }

        val inRangeFrom = starts.min() - HORIZON_MILLIS
        if (now < inRangeFrom) return WeekendWeather.TooEarly(inRangeFrom)

        val body = fetch(lat, lon) ?: return WeekendWeather.Unavailable
        val forecast = runCatching { parseWeekendForecast(body, weekend) }.getOrNull()
            ?: return WeekendWeather.Unavailable
        mutex.withLock { cache[key] = now to forecast }
        return WeekendWeather.Ready(forecast)
    }

    @Volatile
    private var preferred: String? = null

    private suspend fun fetch(lat: Double, lon: Double): String? {
        val order = preferred?.let { listOf(it) + (hosts - it) } ?: hosts
        for (host in order) {
            try {
                return client.getText(forecastUrl(host, lat, lon), USER_AGENT)
                    .also { preferred = host }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {}
        }
        return null
    }

    companion object {
        val DEFAULT_HOSTS = listOf(
            "https://api.open-meteo.com",
            "https://previous-runs-api.open-meteo.com",
        )

        private const val FORECAST_DAYS = 16
        private val HORIZON_MILLIS = TimeUnit.DAYS.toMillis(FORECAST_DAYS - 1L)
        private val FRESH_MILLIS = TimeUnit.HOURS.toMillis(1)
        private const val CONNECT_TIMEOUT_SECONDS = 6L
        private const val USER_AGENT = "Laply/1.0 (Android; +https://github.com/Spottq/Laply)"

        fun forecastUrl(host: String, lat: Double, lon: Double): String =
            "$host/v1/forecast?latitude=${coordinate(lat)}&longitude=${coordinate(lon)}" +
                "&hourly=temperature_2m,precipitation_probability,weather_code,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=$FORECAST_DAYS"

        private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

        private val HOURS_KEPT_BEFORE = TimeUnit.HOURS.toMillis(3)
        private val HOURS_KEPT_AFTER = TimeUnit.HOURS.toMillis(4)

        private val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

        internal fun parseWeekendForecast(body: String, weekend: RaceWeekend): WeekendForecast? {
            val dto = LenientJson.decodeFromString<ForecastDto>(body)
            val zone: ZoneId = dto.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: ZoneOffset.ofTotalSeconds(dto.utcOffsetSeconds)
            fun localOf(utcMillis: Long) = Instant.ofEpochMilli(utcMillis).atZone(zone)

            val weekendDates = weekend.sessions
                .mapNotNull { it.startUtcMillis }
                .map { localOf(it).toLocalDate() }
                .toSortedSet()

            val daily = dto.daily
            val days = daily?.time.orEmpty().mapIndexedNotNull { i, raw ->
                val date = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return@mapIndexedNotNull null
                if (date !in weekendDates) return@mapIndexedNotNull null
                val code = daily?.weatherCode?.getOrNull(i)
                val max = daily?.tempMax?.getOrNull(i)
                if (code == null && max == null) return@mapIndexedNotNull null
                DayForecast(
                    date = date,
                    weatherCode = code?.toInt(),
                    maxTempC = max,
                    minTempC = daily?.tempMin?.getOrNull(i),
                    rainChancePct = daily?.rainMax?.getOrNull(i)?.toInt(),
                )
            }
            if (days.isEmpty()) return null

            val hourly = dto.hourly
            val hourIndex = hourly?.time.orEmpty().withIndex().associate { (i, t) -> t to i }
            val sessions = weekend.sessions.mapNotNull { session ->
                val start = session.startUtcMillis ?: return@mapNotNull null
                val hour = HOUR.format(localOf(start).truncatedTo(ChronoUnit.HOURS))
                val i = hourIndex[hour] ?: return@mapNotNull null
                val forecast = SessionForecast(
                    weatherCode = hourly?.weatherCode?.getOrNull(i)?.toInt(),
                    tempC = hourly?.temp?.getOrNull(i),
                    rainChancePct = hourly?.rain?.getOrNull(i)?.toInt(),
                    isDay = hourly?.isDay?.getOrNull(i)?.let { it > 0.5 } ?: true,
                )
                if (forecast.weatherCode == null && forecast.tempC == null) null
                else session.kind to forecast
            }.toMap()

            val starts = weekend.sessions.mapNotNull { it.startUtcMillis }
            val first = starts.min() - HOURS_KEPT_BEFORE
            val last = starts.max() + HOURS_KEPT_AFTER
            val hours = hourly?.time.orEmpty().mapIndexedNotNull { i, raw ->
                val utc = runCatching {
                    LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli()
                }.getOrNull() ?: return@mapIndexedNotNull null
                if (utc < first || utc > last) return@mapIndexedNotNull null
                HourForecast(
                    utcMillis = utc,
                    weatherCode = hourly?.weatherCode?.getOrNull(i)?.toInt(),
                    tempC = hourly?.temp?.getOrNull(i),
                    rainChancePct = hourly?.rain?.getOrNull(i)?.toInt(),
                    isDay = hourly?.isDay?.getOrNull(i)?.let { it > 0.5 } ?: true,
                ).takeIf { it.weatherCode != null || it.tempC != null }
            }

            return WeekendForecast(days = days, sessions = sessions, hours = hours)
        }
    }

    // ------------------------------------------------------------------- dtos

    @Serializable
    private class ForecastDto(
        @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
        val timezone: String? = null,
        val hourly: HourlyDto? = null,
        val daily: DailyDto? = null,
    )

    @Serializable
    private class HourlyDto(
        val time: List<String> = emptyList(),
        @SerialName("temperature_2m") val temp: List<Double?> = emptyList(),
        @SerialName("precipitation_probability") val rain: List<Double?> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Double?> = emptyList(),
        @SerialName("is_day") val isDay: List<Double?> = emptyList(),
    )

    @Serializable
    private class DailyDto(
        val time: List<String> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Double?> = emptyList(),
        @SerialName("temperature_2m_max") val tempMax: List<Double?> = emptyList(),
        @SerialName("temperature_2m_min") val tempMin: List<Double?> = emptyList(),
        @SerialName("precipitation_probability_max") val rainMax: List<Double?> = emptyList(),
    )
}
