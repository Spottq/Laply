package com.flexy.f1live.ui.live

import com.flexy.f1live.model.RaceWeekend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeetingWeekendTest {

    private fun weekend(round: Int, name: String, country: String, locality: String, circuit: String) =
        RaceWeekend(
            season = 2026, round = round, name = name, country = country, locality = locality,
            circuitName = circuit, countryCode = null, sessions = emptyList(),
        )

    private val barcelona = weekend(9, "Barcelona-Catalunya Grand Prix", "Spain", "Montmeló", "Circuit de Barcelona-Catalunya")
    private val madrid = weekend(16, "Spanish Grand Prix", "Spain", "Madrid", "Madring")
    private val miami = weekend(6, "Miami Grand Prix", "USA", "Miami", "Miami International Autodrome")
    private val austin = weekend(19, "United States Grand Prix", "USA", "Austin", "Circuit of the Americas")
    private val monza = weekend(15, "Italian Grand Prix", "Italy", "Monza", "Autodromo Nazionale di Monza")
    private val calendar = listOf(barcelona, madrid, miami, austin, monza)

    @Test
    fun matchesTheGrandPrixNameFirst() {
        assertEquals(madrid, findMeetingWeekend(calendar, MeetingKey("Spanish Grand Prix", "Spain", "")))
        assertEquals(monza, findMeetingWeekend(calendar, MeetingKey("italian grand prix", "", "")))
    }

    @Test
    fun fallsBackToCountryAndTown() {
        assertEquals(austin, findMeetingWeekend(calendar, MeetingKey("Gran Premio", "USA", "Austin")))
        assertEquals(barcelona, findMeetingWeekend(calendar, MeetingKey("", "Spain", "Barcelona")))
        assertEquals(monza, findMeetingWeekend(calendar, MeetingKey("", "Italy", "")))
    }

    @Test
    fun anAmbiguousCountryMatchesNothing() {
        assertNull(findMeetingWeekend(calendar, MeetingKey("", "USA", "")))
        assertNull(findMeetingWeekend(calendar, MeetingKey("", "", "")))
    }
}
