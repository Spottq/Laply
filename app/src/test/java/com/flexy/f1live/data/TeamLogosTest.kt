package com.flexy.f1live.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamLogosTest {

    @Test
    fun `every 2026 constructor id resolves to its slug`() {
        val expected = mapOf(
            "mclaren" to "mclaren",
            "ferrari" to "ferrari",
            "mercedes" to "mercedes",
            "red_bull" to "redbullracing",
            "aston_martin" to "astonmartin",
            "alpine" to "alpine",
            "williams" to "williams",
            "rb" to "racingbulls",
            "racing_bulls" to "racingbulls",
            "haas" to "haas",
            "audi" to "audi",
            "sauber" to "audi",
            "cadillac" to "cadillac",
        )
        for ((id, slug) in expected) {
            val url = TeamLogos.forConstructorId(id)
            assertEquals(id, expectedUrl(slug), url)
        }
    }

    @Test
    fun `display names from the live feed resolve too`() {
        assertEquals(expectedUrl("redbullracing"), TeamLogos.forTeamName("Oracle Red Bull Racing"))
        assertEquals(expectedUrl("racingbulls"), TeamLogos.forTeamName("Racing Bulls"))
        assertEquals(expectedUrl("haas"), TeamLogos.forTeamName("Haas F1 Team"))
        assertEquals(expectedUrl("audi"), TeamLogos.forTeamName("Kick Sauber"))
        assertEquals(expectedUrl("astonmartin"), TeamLogos.forTeamName("Aston Martin"))
        assertEquals(expectedUrl("mclaren"), TeamLogos.forTeamName("McLaren"))
    }

    @Test
    fun `an unknown constructor has no logo`() {
        assertNull(TeamLogos.forConstructorId("lotus_f1"))
        assertNull(TeamLogos.forTeamName(""))
        assertNull(TeamLogos.forTeamName("Brabham"))
    }

    @Test
    fun `the season appears in both the folder and the file name`() {
        val url = TeamLogos.forConstructorId("ferrari", season = 2026)!!
        assertTrue(url, url.contains("/f1/2026/ferrari/"))
        assertTrue(url, url.endsWith("2026ferrarilogowhite.webp"))
    }

    private fun expectedUrl(slug: String) =
        "https://media.formula1.com/image/upload/c_fit,h_400/q_auto/common/f1/2026/" +
            slug + "/2026" + slug + "logowhite.webp"
}
