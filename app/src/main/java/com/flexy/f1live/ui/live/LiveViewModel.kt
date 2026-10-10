package com.flexy.f1live.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flexy.f1live.data.Graph
import com.flexy.f1live.live.LiveUpdateController
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.WeekendWeather
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

data class UpcomingSession(val weekend: RaceWeekend, val session: ScheduledSession)

class LiveViewModel : ViewModel() {

    val state: StateFlow<LiveSessionState> =
        Graph.liveTiming.state.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = Graph.liveTiming.state.value,
        )

    val isFollowing: StateFlow<Boolean> = LiveUpdateController.followEnabled

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _nextSession = MutableStateFlow<UpcomingSession?>(null)
    val nextSession: StateFlow<UpcomingSession?> = _nextSession.asStateFlow()

    private val _circuit = MutableStateFlow<CircuitOutline?>(null)
    val circuit: StateFlow<CircuitOutline?> = _circuit.asStateFlow()

    private val _nextWeather = MutableStateFlow<WeekendWeather>(WeekendWeather.Loading)
    val nextWeather: StateFlow<WeekendWeather> = _nextWeather.asStateFlow()

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
        viewModelScope.launch {
            _nextSession
                .map { it?.weekend }
                .distinctUntilChanged()
                .collectLatest { weekend ->
                    if (weekend == null) return@collectLatest
                    _nextWeather.value = WeekendWeather.Loading
                    _nextWeather.value = Graph.weather.forecast(weekend)
                }
        }
        loadNextSession()
    }

    fun onScreenVisible() {
        _lastError.value = null
        LiveUpdateController.attachUi()
        refreshNextSessionIfStale()
        refreshWeather()
    }

    private fun refreshWeather() {
        val weekend = _nextSession.value?.weekend ?: return
        viewModelScope.launch {
            val weather = Graph.weather.forecast(weekend)
            if (_nextSession.value?.weekend != weekend) return@launch
            val keepOld = weather is WeekendWeather.Unavailable &&
                _nextWeather.value is WeekendWeather.Ready
            if (!keepOld) _nextWeather.value = weather
        }
    }

    fun refreshNextSessionIfStale() {
        val start = _nextSession.value?.session?.startUtcMillis
        if (start == null || start <= System.currentTimeMillis()) loadNextSession()
    }

    fun onScreenGone() {
        LiveUpdateController.detachUi()
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

internal data class MeetingKey(val name: String, val country: String, val location: String)

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
