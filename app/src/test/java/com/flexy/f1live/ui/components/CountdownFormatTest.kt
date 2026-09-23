package com.flexy.f1live.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The countdown units the Live tab and the widgets share ("in 1d 13h", "in 16h 41m", "in 8 min",
 * "Starting now"): whole units, rounded down, never seconds and never "0 min".
 */
class CountdownFormatTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun daysAndHoursFromADayOut() {
        assertEquals(CountdownUnits.DaysHours(1, 13), countdownUnits(day + 13 * hour + 59 * minute))
        assertEquals(CountdownUnits.DaysHours(1, 0), countdownUnits(day))
        assertEquals(CountdownUnits.DaysHours(12, 3), countdownUnits(12 * day + 3 * hour + 5_000))
    }

    @Test
    fun hoursAndMinutesUnderADay() {
        assertEquals(CountdownUnits.HoursMinutes(16, 41), countdownUnits(16 * hour + 41 * minute + 11_000))
        assertEquals(CountdownUnits.HoursMinutes(23, 59), countdownUnits(day - 1))
        assertEquals(CountdownUnits.HoursMinutes(1, 0), countdownUnits(hour))
    }

    @Test
    fun minutesUnderAnHourNeverZero() {
        assertEquals(CountdownUnits.Minutes(8), countdownUnits(8 * minute + 59_000))
        assertEquals(CountdownUnits.Minutes(59), countdownUnits(hour - 1))
        assertEquals(CountdownUnits.Minutes(1), countdownUnits(20_000))
        assertEquals(CountdownUnits.Minutes(1), countdownUnits(1))
    }

    @Test
    fun startedIsNow() {
        assertEquals(CountdownUnits.Now, countdownUnits(0))
        assertEquals(CountdownUnits.Now, countdownUnits(-5 * minute))
    }
}
