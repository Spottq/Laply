package com.flexy.f1live.data

import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.RaceWeekend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/** Season schedule from Jolpica (https://api.jolpi.ca/ergast/f1/{season}.json). */
interface ScheduleRepository {
    /** Cached in memory and on disk; refresh=true forces a network reload. */
    suspend fun getSeason(season: Int, refresh: Boolean = false): Result<List<RaceWeekend>>
}

/**
 * Official F1 SignalR Core live feed (wss://livetiming.formula1.com/signalrcore).
 * A single shared connection: [state] is the merged, parsed snapshot; [start] connects
 * (idempotent, auto-reconnects with backoff), [stop] disconnects and resets to EMPTY.
 */
interface LiveTimingClient {
    val state: StateFlow<LiveSessionState>
    val errors: Flow<Throwable>
    fun start()
    fun stop()
    val isRunning: Boolean
}

/**
 * The last finished classification, kept so the Live tab has something to show before - or
 * instead of - the network. [peek] must never block: it returns null until [read] has run once.
 */
interface CachedSessionSource {
    fun peek(): LiveSessionState?
    suspend fun read(): LiveSessionState?
}

/** Process-wide singletons wired in F1App.onCreate(). */
object Graph {
    /** The app's shared OkHttpClient (connection pool, timeouts). */
    lateinit var http: OkHttpClient
    lateinit var schedule: ScheduleRepository
    lateinit var liveTiming: LiveTimingClient
    lateinit var sessionResults: SessionResultsRepository
    lateinit var circuitMaps: CircuitMapResolver
}
