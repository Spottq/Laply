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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

enum class StandingsTab { Drivers, Constructors }

object StandingsTabRequests {
    private val pending = MutableStateFlow<StandingsTab?>(null)

    val requests: StateFlow<StandingsTab?> = pending.asStateFlow()

    fun request(tab: StandingsTab) {
        pending.value = tab
    }

    fun take(): StandingsTab? = pending.getAndUpdate { null }
}

data class StandingsUiState(
    val season: Int,
    val tab: StandingsTab = StandingsTab.Drivers,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val round: Int = 0,
    val drivers: List<DriverStanding> = emptyList(),
    val constructors: List<ConstructorStanding> = emptyList(),
    val fromCache: Boolean = false,
    val error: String? = null,
    val titleFight: TitleFight? = null,
    val constructorsFight: TitleFight? = null,
) {
    val isEmpty: Boolean get() = drivers.isEmpty() && constructors.isEmpty()

    val titleCut: Int? get() = titleFight?.contenders?.takeIf { it in 1 until drivers.size }

    val constructorsCut: Int?
        get() = constructorsFight?.contenders?.takeIf { it in 1 until constructors.size }

    val driverLeaderPoints: Double get() = drivers.firstOrNull()?.points?.takeIf { it > 0 } ?: 1.0

    val constructorLeaderPoints: Double
        get() = constructors.firstOrNull()?.points?.takeIf { it > 0 } ?: 1.0
}

class StandingsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(StandingsUiState(season = currentSeason(), loading = true))
    val uiState: StateFlow<StandingsUiState> = _uiState.asStateFlow()

    init {
        load(refresh = false)
        viewModelScope.launch {
            StandingsTabRequests.requests.filterNotNull().collect {
                StandingsTabRequests.take()?.let(::selectTab)
            }
        }
    }

    fun refresh() = load(refresh = true)

    fun retry() = load(refresh = true)

    fun selectTab(tab: StandingsTab) {
        _uiState.update { if (it.tab == tab) it else it.copy(tab = tab) }
    }

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

            val weekends = Graph.schedule.getSeason(season).getOrNull().orEmpty()
            val now = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    titleFight = TitleFight.of(
                        drivers = it.drivers,
                        afterRound = it.round,
                        weekends = weekends,
                        nowUtcMillis = now,
                    ),
                    constructorsFight = TitleFight.ofConstructors(
                        teams = it.constructors,
                        afterRound = it.round,
                        weekends = weekends,
                        nowUtcMillis = now,
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
