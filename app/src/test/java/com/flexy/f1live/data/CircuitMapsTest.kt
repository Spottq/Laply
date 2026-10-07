package com.flexy.f1live.data

import com.flexy.f1live.model.RaceWeekend
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CDN slug table. Every name asserted here was checked against media.formula1.com and answers
 * HTTP 200; the two nulls are circuits F1 has never published a map for.
 */
class CircuitMapsTest {

    @Test
    fun `countries resolve to their verified slug`() {
        val expected = mapOf(
            "Italy" to "Italy",
            "Netherlands" to "Netherlands",
            "Bahrain" to "Bahrain",
            "Australia" to "Australia",
            "Azerbaijan" to "Baku",
            "UAE" to "Abu_Dhabi",
            "UK" to "Great_Britain",
            "Saudi Arabia" to "Saudi_Arabia",
            "Mexico" to "Mexico",
            "Brazil" to "Brazil",
            "Qatar" to "Qatar",
            "Singapore" to "Singapore",
            "Japan" to "Japan",
            "China" to "China",
            "Canada" to "Canada",
            "Austria" to "Austria",
            "Hungary" to "Hungary",
            "Belgium" to "Belgium",
            "Monaco" to "Monaco",
            "Portugal" to "Portugal",
        )
        for ((country, slug) in expected) {
            assertEquals(country, slug, CircuitMaps.f1MapName(weekend(country = country)))
        }
    }

    @Test
    fun `the three American rounds are told apart by circuit`() {
        assertEquals(
            "Miami",
            CircuitMaps.f1MapName(
                weekend(country = "USA", locality = "Miami", circuit = "Miami International Autodrome"),
            ),
        )
        assertEquals(
            "Las_Vegas",
            CircuitMaps.f1MapName(
                weekend(country = "USA", locality = "Las Vegas", circuit = "Las Vegas Strip Circuit"),
            ),
        )
        assertEquals(
            "USA",
            CircuitMaps.f1MapName(
                weekend(country = "USA", locality = "Austin", circuit = "Circuit of the Americas"),
            ),
        )
    }

    @Test
    fun `Barcelona keeps the Spain map and Madrid has none`() {
        assertEquals(
            "Spain",
            CircuitMaps.f1MapName(
                weekend(
                    country = "Spain",
                    locality = "Montmelo",
                    circuit = "Circuit de Barcelona-Catalunya",
                    name = "Barcelona Grand Prix",
                ),
            ),
        )
        assertNull(
            CircuitMaps.f1MapName(
                weekend(
                    country = "Spain",
                    locality = "Madrid",
                    circuit = "Madring",
                    name = "Spanish Grand Prix",
                ),
            ),
        )
    }

    @Test
    fun `circuits with no published map return null instead of a guess`() {
        assertNull(
            CircuitMaps.f1MapName(
                weekend(
                    country = "Malaysia",
                    locality = "Kuala Lumpur",
                    circuit = "Sepang International Circuit",
                    name = "Bahrain Grand Prix in Malaysia",
                ),
            ),
        )
        assertNull(CircuitMaps.f1MapName(weekend(country = "Neverland")))
        assertNull(CircuitMaps.f1MapUrl(weekend(country = "Neverland")))
    }

    @Test
    fun `Imola is Emilia Romagna, not Italy`() {
        assertEquals(
            "Emilia_Romagna",
            CircuitMaps.f1MapName(
                weekend(
                    country = "Italy",
                    locality = "Imola",
                    circuit = "Autodromo Enzo e Dino Ferrari",
                    name = "Emilia Romagna Grand Prix",
                ),
            ),
        )
        assertEquals(
            "Italy",
            CircuitMaps.f1MapName(
                weekend(country = "Italy", locality = "Monza", circuit = "Autodromo Nazionale di Monza"),
            ),
        )
    }

    @Test
    fun `the plain outline uses the season's track set and the circuit slug`() {
        val baku = weekend(country = "Azerbaijan", locality = "Baku", circuit = "Baku City Circuit")
        assertEquals(
            "https://media.formula1.com/image/upload/f_png,w_640/common/f1/2026/track/2026trackbaku.png",
            CircuitMaps.f1OutlineUrl(baku),
        )
        val yas = weekend(country = "UAE", circuit = "Yas Marina Circuit")
        assertEquals("yasmarinacircuit", CircuitMaps.outlineSlug(yas))
        assertNull(CircuitMaps.f1OutlineUrl(weekend(country = "Neverland")))
    }

    @Test
    fun `Sepang uses F1's Kuala Lumpur drawing, which only exists from 2026`() {
        val sepang = weekend(country = "Malaysia", locality = "Kuala Lumpur", circuit = "Sepang International Circuit")
        assertEquals("kualalumpur", CircuitMaps.outlineSlug(sepang))
        assertEquals(
            "https://media.formula1.com/image/upload/f_png,w_640/common/f1/2026/track/2026trackkualalumpur.png",
            CircuitMaps.f1OutlineUrl(sepang.copy(season = 2017)),
        )
        assertEquals(
            "https://media.formula1.com/image/upload/f_png,w_1200/common/f1/2026/track/2026trackkualalumpurdetailed.png",
            CircuitMaps.f1DetailedMapUrl(sepang.copy(season = 2025)),
        )
        assertEquals(15, CircuitMaps.turnsOf(sepang))
    }

    @Test
    fun `the detailed map shares the outline's season and slug`() {
        val baku = weekend(country = "Azerbaijan", locality = "Baku", circuit = "Baku City Circuit")
        assertEquals(
            "https://media.formula1.com/image/upload/f_png,w_1200/common/f1/2026/track/2026trackbakudetailed.png",
            CircuitMaps.f1DetailedMapUrl(baku),
        )
        assertEquals(
            "https://media.formula1.com/image/upload/f_png,w_1200/common/f1/2026/track/2026trackmadringdetailed.png",
            CircuitMaps.f1DetailedMapUrl(weekend(country = "Spain", locality = "Madrid", circuit = "Madring")),
        )
        // Seasons past the newest verified set reuse it.
        assertEquals(
            "https://media.formula1.com/image/upload/f_png,w_1200/common/f1/2026/track/2026trackbakudetailed.png",
            CircuitMaps.f1DetailedMapUrl(baku.copy(season = 2027)),
        )
        assertNull(CircuitMaps.f1DetailedMapUrl(weekend(country = "Neverland")))
    }

    @Test
    fun `turns come from the outline slug`() {
        assertEquals(20, CircuitMaps.turnsOf(weekend(country = "Azerbaijan", locality = "Baku", circuit = "Baku City Circuit")))
        assertEquals(22, CircuitMaps.turnsOf(weekend(country = "Spain", locality = "Madrid", circuit = "Madring")))
        assertEquals(14, CircuitMaps.turnsOf(weekend(country = "Spain", locality = "Montmeló", circuit = "Circuit de Barcelona-Catalunya")))
        assertEquals(11, CircuitMaps.turnsOf(weekend(country = "Italy", locality = "Monza", circuit = "Autodromo Nazionale di Monza")))
        assertEquals(19, CircuitMaps.turnsOf(weekend(country = "Italy", locality = "Imola", circuit = "Autodromo Enzo e Dino Ferrari")))
        assertEquals(27, CircuitMaps.turnsOf(weekend(country = "Saudi Arabia", locality = "Jeddah", circuit = "Jeddah Corniche Circuit")))
        assertEquals(16, CircuitMaps.turnsOf(weekend(country = "UAE", circuit = "Yas Marina Circuit")))
        assertEquals(20, CircuitMaps.turnsOf(weekend(country = "USA", locality = "Austin", circuit = "Circuit of the Americas")))
        assertEquals(17, CircuitMaps.turnsOf(weekend(country = "USA", locality = "Las Vegas", circuit = "Las Vegas Strip Circuit")))
        // No outline, no count: never a guess.
        assertNull(CircuitMaps.turnsOf(weekend(country = "Neverland")))
    }

    @Test
    fun `the url uses the 16 by 9 media path`() {
        val url = CircuitMaps.f1MapUrl(weekend(country = "Italy"))!!
        assertTrue(url, url.startsWith("https://media.formula1.com/image/upload/"))
        assertTrue(url, url.endsWith("Circuit%20maps%2016x9/Italy_Circuit"))
    }

    @Test
    fun `wikipedia titles are taken from the circuit page url`() {
        assertEquals(
            "Circuit_Zandvoort",
            CircuitMaps.wikiTitleOf("https://en.wikipedia.org/wiki/Circuit_Zandvoort"),
        )
        assertEquals(
            "Las_Vegas_Grand_Prix",
            CircuitMaps.wikiTitleOf("https://en.wikipedia.org/wiki/Las_Vegas_Grand_Prix#Circuit"),
        )
        assertNull(CircuitMaps.wikiTitleOf(null))
        assertNull(CircuitMaps.wikiTitleOf("https://example.com/madring"))
    }

    @Test
    fun `the pageimages response yields the thumbnail source`() {
        val body = """
        {"batchcomplete":"","query":{"pages":{"80044756":{"pageid":80044756,"title":"Madring",
          "thumbnail":{"source":"https://upload.wikimedia.org/madring.png","width":1200,"height":806}}}}}
        """.trimIndent()
        assertEquals(
            "https://upload.wikimedia.org/madring.png",
            CircuitMaps.parseWikiThumbnail(parse(body)),
        )
        assertNull(CircuitMaps.parseWikiThumbnail(parse("{\"query\":{\"pages\":{\"1\":{}}}}")))
        assertNull(CircuitMaps.parseWikiThumbnail(parse("{}")))
    }

    private fun parse(body: String) = Json.parseToJsonElement(body) as JsonObject

    private fun weekend(
        country: String,
        locality: String = "",
        circuit: String = "",
        name: String = "Grand Prix",
    ) = RaceWeekend(
        season = 2026,
        round = 1,
        name = name,
        country = country,
        locality = locality,
        circuitName = circuit,
        countryCode = null,
        sessions = emptyList(),
    )
}
