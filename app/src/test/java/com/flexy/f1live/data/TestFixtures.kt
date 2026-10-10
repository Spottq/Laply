package com.flexy.f1live.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal object TestFixtures {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    fun snapshot(): JsonObject = json.parseToJsonElement(read("subscribe_snapshot.json")) as JsonObject
}
