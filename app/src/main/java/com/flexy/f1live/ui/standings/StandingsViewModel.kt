package com.flexy.f1live.ui.standings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flexy.f1live.data.ConstructorStanding
import com.flexy.f1live.data.DriverStanding
import com.flexy.f1live.data.Graph
import com.flexy.f1live.data.JolpicaStandingsRepository
import com.flexy.f1live.data.Standings
import com.flexy.f1live.data.TitleFight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

enum class StandingsTab { Drivers, Constructors }

data class StandingsUiState(
    val season: Int,
    val tab: StandingsTab = StandingsTab.Drivers,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val round: Int = 0,
    val drivers: List<DriverStanding> = emptyList(),
    val constructors: List<ConstructorStanding> = emptyList(),
    /** True while the table on screen came from disk and the network has not confirmed it. */
    val fromCache: Boolean = false,
    val error: String? = null,
    /** Null until the calendar is in, or when it does not fit the standings. */
    val titleFight: TitleFight? = null,
) {
    val isEmpty: Boolean get() = drivers.isEmpty() && constructors.isEmpty()

    /**
     * How many drivers sit above the "out of reach" line, or null for no line: everyone can still
     * win, or there is no calendar to tell.
     */
    val titleCut: Int? get() = titleFight?.contenders?.takeIf { it in 1 until drivers.size }

    /** Leader's points, used to size the gap bars. Never zero, so the bars can divide by it. */
    val driverLeaderPoints: Double get() = drivers.firstOrNull()?.points?.takeIf { it > 0 } ?: 1.0

    val constructorLeaderPoints: Double
        get() = constructors.firstOrNull()?.points?.takeIf { it > 0 } ?: 1.0
}

class StandingsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(StandingsUiState(season = currentSeason(), loading = true))
    val uiState: StateFlow<StandingsUiState> = _uiState.asStateFlow()

    init {
        load(refresh = false)
    }

    fun refresh() = load(refresh = true)

    fun retry() = load(refresh = true)

    fun selectTab(tab: StandingsTab) {
        _uiState.update { if (it.tab == tab) it else it.copy(tab = tab) }
    }

    /**
     * Two steps on purpose: whatever is on disk goes on screen immediately - offline that is the
     * whole answer - and the network then refreshes it. A failed refresh leaves the stored table
     * in place with the "offline" caption rather than replacing it with an error.
     */
    private fun load(refresh: Boolean) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(loading = it.isEmpty, refreshing = refresh && !it.isEmpty, error = null)
            }
            val season = _uiState.value.season

            if (_uiState.value.isEmpty) {
                JolpicaStandingsRepository.cached(season)?.let { stored ->
                    _uiState.update { it.show(stored, fromCache = true) }
                }
            }

            JolpicaStandingsRepository.fetch(season).fold(
                onSuccess = { standings ->
                    _uiState.update { it.show(standings, fromCache = false) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            fromCache = !it.isEmpty,
                            error = if (it.isEmpty) {
                                error.message ?: "Could not load the $season standings"
                            } else {
                                null
                            },
                        )
                    }
                },
            )

            // After the table, not before it: on a first launch the calendar may need the network
            // too, and the standings should not wait for it.
            val weekends = Graph.schedule.getSeason(season).getOrNull().orEmpty()
            _uiState.update {
                it.copy(
                    titleFight = TitleFight.of(
                        drivers = it.drivers,
                        afterRound = it.round,
                        weekends = weekends,
                        nowUtcMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun StandingsUiState.show(standings: Standings, fromCache: Boolean) = copy(
        loading = false,
        refreshing = false,
        round = standings.round,
        drivers = standings.drivers.sortedBy { driver -> driver.position },
        constructors = standings.constructors.sortedBy { team -> team.position },
        fromCache = fromCache,
        error = null,
    )

    private companion object {
        fun currentSeason(): Int = Calendar.getInstance().get(Calendar.YEAR)
    }
}
