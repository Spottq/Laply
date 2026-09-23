package com.flexy.f1live.data

import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.LiveSource
import com.flexy.f1live.model.SessionKind
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Finished sessions on disk, keyed `{season}_{round}_{kind}` - "2026_12_RACE".
 *
 * A classification never changes once the session is over, so an entry is written once and read
 * forever: opening the Dutch GP race from the schedule a second time costs no network at all.
 */
class SessionResultsStore(private val store: JsonStore) {

    suspend fun read(season: Int, round: Int, kind: SessionKind): LiveSessionState? =
        store.read(key(season, round, kind), LiveSessionState.serializer())

    suspend fun write(season: Int, round: Int, kind: SessionKind, state: LiveSessionState): Boolean =
        store.write(key(season, round, kind), LiveSessionState.serializer(), state)

    fun exists(season: Int, round: Int, kind: SessionKind): Boolean =
        store.exists(key(season, round, kind))

    companion object {
        /**
         * Bumped whenever the mapping changes, which retires every stored classification: the
         * entries are written once and never refreshed, so without it a fixed mapping bug would
         * stay on screen forever.
         */
        private const val VERSION = "v2"

        fun key(season: Int, round: Int, kind: SessionKind): String =
            VERSION + "_" + season + "_" + round + "_" + kind
    }
}

/**
 * The single most recent finished session, whichever weekend it belonged to.
 *
 * Read synchronously-ish at start-up so the Live tab paints a classification instead of
 * "No live session" while the feeds are still connecting; see [CompositeLiveTimingClient].
 */
class LastSessionStore(private val store: JsonStore) : CachedSessionSource {

    /** Populated by [preload]; lets [CompositeLiveTimingClient.start] publish without suspending. */
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

        /**
         * Replays are labelled [LiveSource.CACHE] whatever produced them, so the header can say
         * the table came off disk and [CompositeLiveTimingClient] knows the network may replace it.
         */
        fun tagged(state: LiveSessionState) =
            state.copy(isConnected = false, source = LiveSource.CACHE)
    }
}

/** Resolved circuit-map URLs, keyed by `{season}_{round}`; see [CircuitMaps]. */
class CircuitMapStore(private val store: JsonStore) {

    suspend fun read(): Map<String, String> =
        store.read(KEY, Entries) ?: emptyMap()

    suspend fun write(value: Map<String, String>): Boolean = store.write(KEY, Entries, value)

    private companion object {
        const val KEY = "circuit_maps"
        val Entries = MapSerializer(String.serializer(), String.serializer())
    }
}
