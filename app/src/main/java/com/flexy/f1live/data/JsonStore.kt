package com.flexy.f1live.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class JsonStore(
    private val directory: File,
    private val json: Json = DefaultJson,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun <T> read(name: String, serializer: KSerializer<T>): T? = withContext(io) {
        val file = fileOf(name)
        if (!file.isFile) return@withContext null
        try {
            json.decodeFromString(serializer, file.readText())
        } catch (_: Exception) {
            runCatching { file.delete() }
            null
        }
    }

    suspend fun <T> write(name: String, serializer: KSerializer<T>, value: T): Boolean =
        withContext(io) {
            val file = fileOf(name)
            val temp = File(file.parentFile, file.name + ".tmp")
            try {
                file.parentFile?.mkdirs()
                temp.writeText(json.encodeToString(serializer, value))
                moveAtomically(temp, file)
                true
            } catch (_: Exception) {
                runCatching { temp.delete() }
                false
            }
        }

    fun exists(name: String): Boolean = fileOf(name).isFile

    fun delete(name: String) {
        runCatching { fileOf(name).delete() }
    }

    fun fileOf(name: String): File = File(directory, sanitize(name) + ".json")

    private fun moveAtomically(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun sanitize(name: String): String =
            name.map { if (it.isLetterOrDigit() || it == '_' || it == '-' || it == '.') it else '_' }
                .joinToString("")
                .take(120)
                .ifEmpty { "unnamed" }
    }
}
