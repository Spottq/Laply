package com.flexy.f1live.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonMergeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    private fun merged(base: String, delta: String): JsonObject =
        JsonMerge.merge(obj(base), obj(delta)) as JsonObject

    @Test
    fun `object into object merges recursively and keeps untouched keys`() {
        val result = merged(
            """{"Lines":{"1":{"Position":"2","NumberOfLaps":9,"BestLapTime":{"Value":"1:22.659","Lap":6}}}}""",
            """{"Lines":{"1":{"Position":"1","BestLapTime":{"Value":"1:22.067"}}}}""",
        )
        val line = result["Lines"]!!.jsonObject["1"]!!.jsonObject
        assertEquals("1", line["Position"]!!.jsonPrimitive.content)
        assertEquals(9, line["NumberOfLaps"]!!.jsonPrimitive.content.toInt())
        assertEquals("1:22.067", line["BestLapTime"]!!.jsonObject["Value"]!!.jsonPrimitive.content)
        // nested scalar not mentioned in the delta survives
        assertEquals(6, line["BestLapTime"]!!.jsonObject["Lap"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `new keys are added and scalars replaced`() {
        val result = merged("""{"a":1,"b":"x"}""", """{"b":"y","c":true}""")
        assertEquals(1, result["a"]!!.jsonPrimitive.content.toInt())
        assertEquals("y", result["b"]!!.jsonPrimitive.content)
        assertEquals(true, result["c"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `index-keyed object merges into an existing array element`() {
        val result = merged(
            """{"Sectors":[{"Value":"26.9","Segments":[{"Status":0},{"Status":0}]},{"Value":"27.8"}]}""",
            """{"Sectors":{"0":{"Segments":{"1":{"Status":2048}}}}}""",
        )
        val sectors = result["Sectors"]!!.jsonArray
        assertEquals(2, sectors.size)
        assertEquals("26.9", sectors[0].jsonObject["Value"]!!.jsonPrimitive.content)
        val segments = sectors[0].jsonObject["Segments"]!!.jsonArray
        assertEquals(0, segments[0].jsonObject["Status"]!!.jsonPrimitive.content.toInt())
        assertEquals(2048, segments[1].jsonObject["Status"]!!.jsonPrimitive.content.toInt())
        assertEquals("27.8", sectors[1].jsonObject["Value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `index past the end appends to the array`() {
        val result = merged(
            """{"Stints":[{"Compound":"SOFT"},{"Compound":"SOFT"}]}""",
            """{"Stints":{"2":{"Compound":"MEDIUM","TotalLaps":2}}}""",
        )
        val stints = result["Stints"]!!.jsonArray
        assertEquals(3, stints.size)
        assertEquals("MEDIUM", stints[2].jsonObject["Compound"]!!.jsonPrimitive.content)
        assertEquals(2, stints[2].jsonObject["TotalLaps"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `race control messages arrive index-keyed and append to the array`() {
        val result = merged(
            """{"RaceControlMessages":{"Messages":[{"Message":"GREEN LIGHT"}]}}""",
            """{"RaceControlMessages":{"Messages":{"1":{"Utc":"2026-09-05T14:33:00","Message":"CHEQUERED FLAG"}}}}""",
        )
        val messages = result["RaceControlMessages"]!!.jsonObject["Messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("GREEN LIGHT", messages[0].jsonObject["Message"]!!.jsonPrimitive.content)
        assertEquals("CHEQUERED FLAG", messages[1].jsonObject["Message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deleted removes object keys`() {
        val result = merged(
            """{"Lines":{"1":{"x":1},"5":{"x":2},"9":{"x":3}}}""",
            """{"Lines":{"_deleted":["5","9"],"1":{"x":7}}}""",
        )
        val lines = result["Lines"]!!.jsonObject
        assertEquals(setOf("1"), lines.keys)
        assertEquals(7, lines["1"]!!.jsonObject["x"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `deleted removes array indices`() {
        val result = merged(
            """{"Messages":[{"m":"a"},{"m":"b"},{"m":"c"}]}""",
            """{"Messages":{"_deleted":["1"]}}""",
        )
        val messages = result["Messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("a", messages[0].jsonObject["m"]!!.jsonPrimitive.content)
        assertEquals("c", messages[1].jsonObject["m"]!!.jsonPrimitive.content)
    }

    @Test
    fun `arrays in the delta replace arrays in the base`() {
        val result = merged("""{"NoEntries":[20,15,10]}""", """{"NoEntries":[22,16,10]}""")
        val entries = result["NoEntries"]!!.jsonArray
        assertEquals(listOf(22, 16, 10), entries.map { it.jsonPrimitive.content.toInt() })
    }

    @Test
    fun `merging into a missing base keeps the delta and drops the deleted marker`() {
        val result = JsonMerge.merge(null, obj("""{"a":1,"_deleted":["a"]}""")) as JsonObject
        assertTrue(result.isEmpty())
        val kept = JsonMerge.merge(null, obj("""{"a":1}""")) as JsonObject
        assertEquals(1, kept["a"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `mergeTopic only touches the given topic`() {
        val root = obj("""{"TimingData":{"Lines":{"1":{"Position":"2"}}},"TrackStatus":{"Status":"1"}}""")
        val result = JsonMerge.mergeTopic(root, "TrackStatus", obj("""{"Status":"2","Message":"Yellow"}"""))
        assertEquals("2", result["TrackStatus"]!!.jsonObject["Status"]!!.jsonPrimitive.content)
        assertEquals("Yellow", result["TrackStatus"]!!.jsonObject["Message"]!!.jsonPrimitive.content)
        assertEquals(
            "2",
            result["TimingData"]!!.jsonObject["Lines"]!!.jsonObject["1"]!!
                .jsonObject["Position"]!!.jsonPrimitive.content,
        )
        // the input document is untouched
        assertEquals("1", root["TrackStatus"]!!.jsonObject["Status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `real feed deltas apply to the captured snapshot`() {
        val snapshot = TestFixtures.snapshot()
        val deltas = Json.parseToJsonElement(TestFixtures.read("feed_samples.json")) as JsonArray
        var document = snapshot
        for (frame in deltas) {
            val args = frame.jsonArray
            val topic = args[0].jsonPrimitive.content
            document = JsonMerge.mergeTopic(document, topic, args[1])
        }
        // every topic of the snapshot survives 120 deltas
        assertTrue(document.keys.containsAll(snapshot.keys))
        val lines = document["TimingData"]!!.jsonObject["Lines"]!!.jsonObject
        assertEquals(22, lines.size)
        // sectors stayed arrays after index-keyed merges
        val sectors = lines["12"]!!.jsonObject["Sectors"]!!.jsonArray
        assertEquals(3, sectors.size)
        assertFalse(sectors[0].jsonObject["Segments"]!!.jsonArray.isEmpty())
        assertNull(document["CarData.z"])
    }
}
