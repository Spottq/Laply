package com.flexy.f1live.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deep merge for the F1 SignalR feed.
 *
 * The "Subscribe" snapshot is a full JSON document; every `feed` invocation afterwards carries a
 * *partial* object that has to be merged into it. The feed has a few quirks:
 *
 *  * object into object -> recursive merge (keys not present in the delta are kept)
 *  * object into array   -> the delta object is keyed by array index ("0", "1", "2"); the entry is
 *                           merged into the element at that index, or appended when the index is at
 *                           or past the end (gaps are padded with `null`)
 *  * anything else       -> straight replacement (scalars, arrays, type changes)
 *  * `"_deleted"`        -> list of keys (or array indices) to remove from the *target* container
 *
 * Pure JVM, no Android dependencies, so it can be unit tested directly.
 */
object JsonMerge {

    private const val DELETED_KEY = "_deleted"

    /** Merges [delta] into [base] and returns a new element. [base] may be null (nothing known yet). */
    fun merge(base: JsonElement?, delta: JsonElement): JsonElement = when {
        delta !is JsonObject -> delta
        base is JsonObject -> mergeObject(base, delta)
        base is JsonArray -> mergeIntoArray(base, delta)
        else -> mergeObject(JsonObject(emptyMap()), delta)
    }

    /** Convenience overload for the common "merge a topic delta into the snapshot root" case. */
    fun mergeTopic(root: JsonObject, topic: String, delta: JsonElement): JsonObject {
        val merged = merge(root[topic], delta)
        return JsonObject(LinkedHashMap(root).apply { put(topic, merged) })
    }

    private fun mergeObject(base: JsonObject, delta: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(base)
        for ((key, value) in delta) {
            if (key == DELETED_KEY) continue
            out[key] = merge(base[key], value)
        }
        for (key in deletedKeys(delta)) out.remove(key)
        return JsonObject(out)
    }

    private fun mergeIntoArray(base: JsonArray, delta: JsonObject): JsonElement {
        val entries = delta.filterKeys { it != DELETED_KEY }
        // Only index-keyed objects address an array; anything else replaces it wholesale.
        if (entries.isNotEmpty() && entries.keys.any { it.toIndexOrNull() == null }) {
            return mergeObject(JsonObject(emptyMap()), delta)
        }
        val out = base.toMutableList()
        for ((key, value) in entries.entries.sortedBy { it.key.toIndexOrNull() ?: 0 }) {
            val index = key.toIndexOrNull() ?: continue
            while (out.size <= index) out.add(JsonNull)
            val current = out[index].takeUnless { it is JsonNull }
            out[index] = merge(current, value)
        }
        deletedKeys(delta)
            .mapNotNull { it.toIndexOrNull() }
            .distinct()
            .sortedDescending()
            .forEach { if (it in out.indices) out.removeAt(it) }
        return JsonArray(out)
    }

    /** `_deleted` arrives either as an array of keys or (rarely) as an index-keyed object. */
    private fun deletedKeys(delta: JsonObject): List<String> =
        when (val marker = delta[DELETED_KEY]) {
            is JsonArray -> marker.mapNotNull { it.asKeyOrNull() }
            is JsonObject -> marker.values.mapNotNull { it.asKeyOrNull() }
            is JsonPrimitive -> listOfNotNull(marker.asKeyOrNull())
            else -> emptyList()
        }

    private fun JsonElement.asKeyOrNull(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun String.toIndexOrNull(): Int? = toIntOrNull()?.takeIf { it >= 0 }
}
