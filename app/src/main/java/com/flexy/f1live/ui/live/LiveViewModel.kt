package com.flexy.f1live.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flexy.f1live.data.Graph
import com.flexy.f1live.live.LiveUpdateController
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.ui.components.CircuitOutline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/** The soonest session that has not started yet, with the weekend it belongs to. */
data class UpcomingSession(val weekend: RaceWeekend, val session: ScheduledSession)

class LiveViewModel : ViewModel() {

    val state: StateFlow<LiveSessionState> =
        Graph.liveTiming.state.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = Graph.liveTiming.state.value,
        )

    /** The persisted Follow opt-in - not whether a Live Update happens to be showing right now. */
    val isFollowing: StateFlow<Boolean> = LiveUpdateController.followEnabled

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _nextSession = MutableStateFlow<UpcomingSession?>(null)
    val nextSession: StateFlow<UpcomingSession?> = _nextSession.asStateFlow()

    /**
     * The circuit of the meeting on screen, for the large-screen layout's circuit card. Resolved
     * once per meeting: the live state only names it, the calendar supplies the circuit.
     */
    private val _circuit = MutableStateFlow<CircuitOutline?>(null)
    val circuit: StateFlow<CircuitOutline?> = _circuit.asStateFlow()

    init {
        viewModelScope.launch {
            Graph.liveTiming.state
                .map { MeetingKey(it.meetingName, it.meetingCountry, it.meetingLocation) }
                .distinctUntilChanged()
                .collectLatest { meeting -> _circuit.value = resolveCircuit(meeting) }
        }
        viewModelScope.launch {
            Graph.liveTiming.errors.collect { error ->
                _lastError.value = error.message ?: error::class.simpleName
            }
        }
        loadNextSession()
    }

    /** Called when the Live screen becomes visible. */
    fun onScreenVisible() {
        _lastError.value = null
        LiveUpdateController.uiActive = true
        Graph.liveTiming.start()
        refreshNextSessionIfStale()
    }

    /**
     * Recomputes [nextSession] when it is missing or has already started: it is picked once, so a
     * session that ran while the screen stayed open would otherwise still be announced as "next".
     */
    fun refreshNextSessionIfStale() {
        val start = _nextSession.value?.session?.startUtcMillis
        if (start == null || start <= System.currentTimeMillis()) loadNextSession()
    }

    /**
     * Called when the Live screen leaves composition. The SignalR connection is a shared
     * singleton: while the Live Update runs, the foreground service owns it and it must stay alive.
     */
    fun onScreenGone() {
        LiveUpdateController.uiActive = false
        if (!LiveUpdateController.serviceRunning.value) Graph.liveTiming.stop()
    }

    fun retry() {
        _lastError.value = null
        Graph.liveTiming.start()
        loadNextSession()
    }

    private suspend fun resolveCircuit(meeting: MeetingKey): CircuitOutline? {
        if (meeting.name.isBlank() && meeting.country.isBlank()) return null
        val season = Calendar.getInstance().get(Calendar.YEAR)
        val weekends = Graph.schedule.getSeason(season).getOrNull() ?: return null
        val weekend = findMeetingWeekend(weekends, meeting) ?: return null
        // The Wikipedia lookup only runs for the circuits F1 publishes no outline for.
        val fallback = if (CircuitOutline.needsFallback(weekend)) {
            Graph.circuitMaps.resolve(weekend)
        } else {
            null
        }
        return CircuitOutline.of(weekend, fallback)
    }

    private fun loadNextSession() {
        viewModelScope.launch {
            val season = Calendar.getInstance().get(Calendar.YEAR)
            val now = System.currentTimeMillis()
            val weekends = Graph.schedule.getSeason(season).getOrNull() ?: return@launch
            _nextSession.value = weekends
                .flatMap { weekend -> weekend.sessions.map { UpcomingSession(weekend, it) } }
                .filter { (it.session.startUtcMillis ?: 0L) > now }
                .minByOrNull { it.session.startUtcMillis ?: Long.MAX_VALUE }
        }
    }
}

/** What the live feed says about the meeting: enough to find it in the season calendar. */
internal data class MeetingKey(val name: String, val country: String, val location: String)

/**
 * The calendar weekend a live meeting belongs to: by Grand Prix name first, then by country - and,
 * for countries with more than one round, by the town or the circuit.
 */
internal fun findMeetingWeekend(weekends: List<RaceWeekend>, meeting: MeetingKey): RaceWeekend? {
    if (meeting.name.isNotBlank()) {
        weekends.firstOrNull { it.name.equals(meeting.name, ignoreCase = true) }?.let { return it }
    }
    if (meeting.country.isBlank()) return null
    val inCountry = weekends.filter { it.country.equals(meeting.country, ignoreCase = true) }
    if (meeting.location.isNotBlank()) {
        inCountry.firstOrNull {
            it.locality.equals(meeting.location, ignoreCase = true) ||
                it.circuitName.contains(meeting.location, ignoreCase = true)
        }?.let { return it }
    }
    return inCountry.singleOrNull()
}
