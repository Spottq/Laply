package com.flexy.f1live.data

import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.RaceWeekend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

interface ScheduleRepository {
    suspend fun getSeason(season: Int, refresh: Boolean = false): Result<List<RaceWeekend>>
}

interface LiveTimingClient {
    val state: StateFlow<LiveSessionState>
    val errors: Flow<Throwable>
    fun start()
    fun stop()
    val isRunning: Boolean
}

interface CachedSessionSource {
    fun peek(): LiveSessionState?
    suspend fun read(): LiveSessionState?
}

object Graph {
    lateinit var http: OkHttpClient
    lateinit var schedule: ScheduleRepository
    lateinit var liveTiming: LiveTimingClient
    lateinit var sessionResults: SessionResultsRepository
    lateinit var circuitMaps: CircuitMapResolver
    lateinit var weather: WeatherRepository
}
