package com.flexy.f1live.data

import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.LiveSource
import com.flexy.f1live.model.SessionKind
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

class SessionResultsStore(private val store: JsonStore) {

    suspend fun read(season: Int, round: Int, kind: SessionKind): LiveSessionState? =
        store.read(key(season, round, kind), LiveSessionState.serializer())?.let(DriverHeadshots::refresh)

    suspend fun write(season: Int, round: Int, kind: SessionKind, state: LiveSessionState): Boolean =
        store.write(key(season, round, kind), LiveSessionState.serializer(), state)

    fun exists(season: Int, round: Int, kind: SessionKind): Boolean =
        store.exists(key(season, round, kind))

    companion object {
        // Bump on every mapping change: stored classifications are never refreshed.
        private const val VERSION = "v2"

        fun key(season: Int, round: Int, kind: SessionKind): String =
            VERSION + "_" + season + "_" + round + "_" + kind
    }
}

class LastSessionStore(private val store: JsonStore) : CachedSessionSource {

    @Volatile
    private var memory: LiveSessionState? = null

    @Volatile
    private var preloaded = false

    override fun peek(): LiveSessionState? = memory

    suspend fun preload(): LiveSessionState? = read()

    override suspend fun read(): LiveSessionState? {
        memory?.let { return it }
        if (preloaded) return null
        val stored = store.read(KEY, LiveSessionState.serializer())?.let(::tagged)
        preloaded = true
        memory = stored
        return stored
    }

    suspend fun write(state: LiveSessionState): Boolean {
        memory = tagged(state)
        preloaded = true
        return store.write(KEY, LiveSessionState.serializer(), state)
    }

    private companion object {
        const val KEY = "last_session"

        fun tagged(state: LiveSessionState) =
            DriverHeadshots.refresh(state).copy(isConnected = false, source = LiveSource.CACHE)
    }
}

class CircuitMapStore(private val store: JsonStore) {

    suspend fun read(): Map<String, String> =
        store.read(KEY, Entries) ?: emptyMap()

    suspend fun write(value: Map<String, String>): Boolean = store.write(KEY, Entries, value)

    private companion object {
        const val KEY = "circuit_maps"
        val Entries = MapSerializer(String.serializer(), String.serializer())
    }
}
