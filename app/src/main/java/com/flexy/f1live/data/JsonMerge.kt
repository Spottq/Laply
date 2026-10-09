package com.flexy.f1live.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object JsonMerge {

    private const val DELETED_KEY = "_deleted"

    fun merge(base: JsonElement?, delta: JsonElement): JsonElement = when {
        delta !is JsonObject -> delta
        base is JsonObject -> mergeObject(base, delta)
        base is JsonArray -> mergeIntoArray(base, delta)
        else -> mergeObject(JsonObject(emptyMap()), delta)
    }

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
