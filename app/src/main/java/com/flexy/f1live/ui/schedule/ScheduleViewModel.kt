package com.flexy.f1live.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flexy.f1live.data.Graph
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.WeekendForecast
import com.flexy.f1live.model.WeekendWeather
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class ScheduleUiState(
    val season: Int,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val upcoming: List<RaceWeekend> = emptyList(),
    val completed: List<RaceWeekend> = emptyList(),
    /** Round number of the next weekend, highlighted and expanded by default. */
    val nextRound: Int? = null,
    val error: String? = null,
    /** The current weekend's forecast, by round, once the forecast reaches it. */
    val forecasts: Map<Int, WeekendForecast> = emptyMap(),
) {
    val isEmpty: Boolean get() = upcoming.isEmpty() && completed.isEmpty()
}

class ScheduleViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduleUiState(season = currentSeason(), loading = true))
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    init {
        load(refresh = false)
    }

    fun refresh() = load(refresh = true)

    fun retry() = load(refresh = true)

    private fun load(refresh: Boolean) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(loading = it.isEmpty, refreshing = refresh && !it.isEmpty, error = null)
            }
            val season = _uiState.value.season
            val result = Graph.schedule.getSeason(season, refresh)
            result.fold(
                onSuccess = { weekends ->
                    val fresh = partition(season, weekends)
                    val shown = fresh.upcoming.take(WEATHER_WEEKENDS).map { it.round }.toSet()
                    _uiState.value = fresh.copy(forecasts = _uiState.value.forecasts.filterKeys { it in shown })
                    loadForecasts(_uiState.value.upcoming)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = error.message ?: "Could not load the $season schedule",
                        )
                    }
                },
            )
        }
    }

    /**
     * The forecast for the current weekend only; the next one gets its own once this one is over
     * and [partition] has moved it to the completed list.
     */
    private suspend fun loadForecasts(upcoming: List<RaceWeekend>) {
        for (weekend in upcoming.take(WEATHER_WEEKENDS)) {
            val weather = Graph.weather.forecast(weekend)
            if (weather is WeekendWeather.Ready) {
                _uiState.update { it.copy(forecasts = it.forecasts + (weekend.round to weather.forecast)) }
            }
        }
    }

    private fun partition(season: Int, weekends: List<RaceWeekend>): ScheduleUiState {
        val now = System.currentTimeMillis()
        val sorted = weekends.sortedBy { it.round }
        // A weekend counts as done once its last known session has started.
        val (completed, upcoming) = sorted.partition { weekend ->
            val last = weekend.sessions.mapNotNull { it.startUtcMillis }.maxOrNull()
                ?: weekend.raceStartUtcMillis
            last != null && last + SESSION_GRACE_MILLIS < now
        }
        return ScheduleUiState(
            season = season,
            loading = false,
            refreshing = false,
            upcoming = upcoming,
            completed = completed.asReversed(),
            nextRound = upcoming.firstOrNull()?.round,
            error = null,
        )
    }

    private companion object {
        /** Only the weekend under way, or the very next one between weekends. */
        const val WEATHER_WEEKENDS = 1

        /** Sessions stay "current" for a while after they start. */
        const val SESSION_GRACE_MILLIS = 3L * 60 * 60 * 1000

        fun currentSeason(): Int = Calendar.getInstance().get(Calendar.YEAR)
    }
}
