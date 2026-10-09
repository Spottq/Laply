package com.flexy.f1live.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverHeadshotsTest {

    @Test
    fun correctedDriversUseTheCurrentMediaLibrary() {
        assertTrue(DriverHeadshots.forTla("LIN")!!.contains("/2026/racingbulls/arvlin01/"))
        assertTrue(DriverHeadshots.forTla("per")!!.contains("/2026/cadillac/serper01/"))
        assertTrue(DriverHeadshots.forTla("HUL")!!.contains("/2026/audi/nichul01/"))
    }

    @Test
    fun accentedTlasFromEspnMatch() {
        assertEquals(DriverHeadshots.forTla("HUL"), DriverHeadshots.forTla("HÜL"))
        assertEquals(DriverHeadshots.forTla("PER"), DriverHeadshots.forTla("PÉR"))
    }

    @Test
    fun correctedPortraitWinsOverTheFeedUrl() {
        val feed = "https://media.formula1.com/d_driver_fallback_image.png/content/dam/fom-website/" +
            "drivers/S/SERPER01_Sergio_Perez/serper01.png.transform/1col/image.png"
        assertEquals(DriverHeadshots.forTla("PER"), DriverHeadshots.resolve("PER", feed))
    }

    @Test
    fun otherDriversKeepTheirUrl() {
        assertEquals("https://example.com/nor.png", DriverHeadshots.resolve("NOR", "https://example.com/nor.png"))
        assertTrue(DriverHeadshots.forTla("NOR")!!.contains("LANNOR01_Lando_Norris"))
    }
}
