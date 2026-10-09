package com.flexy.f1live.data

import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.Calendar

class SessionPersister(
    private val scope: CoroutineScope,
    private val results: SessionResultsStore,
    private val last: LastSessionStore,
    private val schedule: ScheduleRepository,
    private val onSaved: (LiveSessionState) -> Unit = {},
    private val minWriteIntervalMs: Long = MIN_WRITE_INTERVAL_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val pending = MutableStateFlow<LiveSessionState?>(null)

    private var savedFingerprint: LiveSessionState? = null

    fun attach(states: Flow<LiveSessionState>) {
        scope.launch {
            states.collect { state -> if (isSaveable(state)) pending.value = state }
        }
        scope.launch {
            pending.filterNotNull().collect { candidate ->
                val fingerprint = fingerprintOf(candidate)
                if (fingerprint == savedFingerprint) return@collect
                savedFingerprint = fingerprint
                save(candidate)
                delay(minWriteIntervalMs)
            }
        }
    }

    private suspend fun save(state: LiveSessionState) {
        val stored = state.copy(isConnected = false)
        last.write(stored)
        resolveRound(stored)?.let { (season, round) ->
            results.write(season, round, stored.sessionKind, stored)
        }
        onSaved(stored)
    }

    private suspend fun resolveRound(state: LiveSessionState): Pair<Int, Int>? {
        if (state.sessionKind == SessionKind.UNKNOWN) return null
        val season = Calendar.getInstance().apply { timeInMillis = nowMillis() }.get(Calendar.YEAR)
        val weekends = schedule.getSeason(season).getOrNull() ?: return null
        val weekend = weekends.firstOrNull { matches(it, state) } ?: return null
        return weekend.season to weekend.round
    }

    private fun matches(weekend: RaceWeekend, state: LiveSessionState): Boolean {
        val name = state.meetingName.trim()
        if (name.isNotEmpty() && weekend.name.trim().equals(name, ignoreCase = true)) return true
        val country = state.meetingCountry.trim()
        val location = state.meetingLocation.trim()
        val countryMatches = country.isNotEmpty() &&
            weekend.country.trim().equals(country, ignoreCase = true)
        val locationMatches = location.isNotEmpty() &&
            weekend.locality.trim().equals(location, ignoreCase = true)
        if (!countryMatches && !locationMatches) return false
        val start = weekend.sessions.mapNotNull { it.startUtcMillis }.minOrNull() ?: return countryMatches
        val end = weekend.sessions.mapNotNull { it.startUtcMillis }.maxOrNull() ?: return countryMatches
        val stamp = state.lastUpdateUtcMillis.takeIf { it > 0 } ?: nowMillis()
        return stamp in (start - WEEKEND_WINDOW_MS)..(end + WEEKEND_WINDOW_MS)
    }

    private companion object {
        const val MIN_WRITE_INTERVAL_MS = 5_000L

        const val WEEKEND_WINDOW_MS = 3L * 24 * 60 * 60 * 1000

        fun isSaveable(state: LiveSessionState): Boolean =
            state.drivers.isNotEmpty() && when (state.status) {
                SessionStatus.FINISHED, SessionStatus.FINALISED, SessionStatus.ENDS -> true
                else -> false
            }

        fun fingerprintOf(state: LiveSessionState): LiveSessionState =
            state.copy(isConnected = false, lastUpdateUtcMillis = 0L)
    }
}
