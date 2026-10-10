package com.flexy.f1live.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DriverNamesTest {

    @Test
    fun `Antonelli takes the initial of the name he races under`() {
        assertEquals("K. Antonelli", DriverNames.short("Andrea Kimi", "Antonelli"))
    }

    @Test
    fun `other drivers take the first letter of their given name`() {
        assertEquals("M. Verstappen", DriverNames.short("Max", "Verstappen"))
        assertEquals("C. Sainz", DriverNames.short(" carlos ", "Sainz "))
    }

    @Test
    fun `no given name leaves the family name`() {
        assertEquals("Hamilton", DriverNames.short("", "Hamilton"))
    }
}
