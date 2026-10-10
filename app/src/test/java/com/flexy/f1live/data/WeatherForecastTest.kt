package com.flexy.f1live.data

import com.flexy.f1live.model.HourForecast
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.WeekendForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WeatherForecastTest {

    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli()

    private val austin = RaceWeekend(
        season = 2026, round = 19, name = "United States Grand Prix", country = "USA",
        locality = "Austin", circuitName = "Circuit of the Americas", countryCode = "us",
        sessions = listOf(
            ScheduledSession(SessionKind.PRACTICE1, "Practice 1", utc("2026-10-23T17:30:00Z")),
            ScheduledSession(SessionKind.SPRINT_QUALIFYING, "Sprint Qualifying", utc("2026-10-24T01:00:00Z")),
            ScheduledSession(SessionKind.RACE, "Race", utc("2026-10-25T19:00:00Z")),
        ),
        latitude = 30.1328, longitude = -97.6411,
    )

    private val body = """
        {
          "utc_offset_seconds": -18000,
          "timezone": "America/Chicago",
          "hourly": {
            "time": ["2026-10-23T12:00", "2026-10-23T20:00", "2026-10-25T14:00"],
            "temperature_2m": [27.4, 22.1, 29.6],
            "precipitation_probability": [10, 55, 0],
            "weather_code": [2, 61, 0],
            "is_day": [1, 0, 1]
          },
          "daily": {
            "time": ["2026-10-22", "2026-10-23", "2026-10-24", "2026-10-25", "2026-10-26"],
            "weather_code": [3, 61, 1, 0, null],
            "temperature_2m_max": [30.0, 28.2, 29.0, 31.5, null],
            "temperature_2m_min": [19.0, 18.4, 17.0, 18.9, null],
            "precipitation_probability_max": [5, 60, 10, 3, 9]
          }
        }
    """.trimIndent()

    @Test
    fun keepsTheWeekendDaysInLocalTime() {
        val forecast = WeatherRepository.parseWeekendForecast(body, austin)!!
        assertEquals(
            listOf(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 25)),
            forecast.days.map { it.date },
        )
        val friday = forecast.days.first()
        assertEquals(61, friday.weatherCode)
        assertEquals(28.2, friday.maxTempC!!, 0.0)
        assertEquals(60, friday.rainChancePct)
    }

    @Test
    fun readsTheHourEachSessionStartsIn() {
        val sessions = WeatherRepository.parseWeekendForecast(body, austin)!!.sessions
        assertEquals(27.4, sessions.getValue(SessionKind.PRACTICE1).tempC!!, 0.0)
        assertEquals(55, sessions.getValue(SessionKind.SPRINT_QUALIFYING).rainChancePct)
        assertEquals(false, sessions.getValue(SessionKind.SPRINT_QUALIFYING).isDay)
        assertEquals(0, sessions.getValue(SessionKind.RACE).weatherCode)
    }

    @Test
    fun keepsTheHoursInUtc() {
        val hours = WeatherRepository.parseWeekendForecast(body, austin)!!.hours
        assertEquals(utc("2026-10-23T17:00:00Z"), hours.first().utcMillis)
        assertEquals(false, hours[1].isDay)
    }

    @Test
    fun aSessionGetsFourHoursFromTwoHoursBefore() {
        val start = utc("2026-10-23T11:30:00Z")
        val hours = (6..16).map { h ->
            HourForecast(utc("2026-10-23T%02d:00:00Z".format(h)), 0, h.toDouble(), 0)
        }
        val forecast = WeekendForecast(days = emptyList(), sessions = emptyMap(), hours = hours)
        assertEquals(listOf(10.0, 11.0, 12.0, 13.0), forecast.hoursAround(start).map { it.tempC })
        assertEquals(13.0, forecast.hoursAround(utc("2026-10-23T15:00:00Z")).first().tempC!!, 0.0)
    }

    @Test
    fun aForecastThatMissesTheWeekendIsNull() {
        val later = austin.copy(
            sessions = austin.sessions.map {
                it.copy(startUtcMillis = it.startUtcMillis!! + 14L * 24 * 60 * 60 * 1000)
            },
        )
        assertNull(WeatherRepository.parseWeekendForecast(body, later))
    }
}
