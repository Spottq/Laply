package com.flexy.f1live.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.flexy.f1live.data.Graph
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResultsUiState(
    val loading: Boolean = true,
    val weekend: RaceWeekend? = null,
    val session: ScheduledSession? = null,
    val state: LiveSessionState? = null,
    /** Racing number credited with the fastest lap, shown as an "FL" chip on that row. */
    val fastestLapRacingNumber: String? = null,
    val mapUrl: String? = null,
    val error: String? = null,
) {
    val isRace: Boolean
        get() = session?.kind == SessionKind.RACE || session?.kind == SessionKind.SPRINT

    val isQualifying: Boolean
        get() = session?.kind == SessionKind.QUALIFYING ||
            session?.kind == SessionKind.SPRINT_QUALIFYING

    val title: String get() = weekend?.name.orEmpty()

    val drivers get() = state?.drivers.orEmpty()
}

/**
 * One finished session, addressed by `{season}/{round}/{kind}` from the schedule.
 *
 * The weekend itself comes from the season calendar (already cached by the schedule screen), the
 * classification from [com.flexy.f1live.data.SessionResultsRepository] - disk first, network
 * second - and the track map from [com.flexy.f1live.data.CircuitMapResolver].
 */
class ResultsViewModel(
    private val season: Int,
    private val round: Int,
    private val kind: SessionKind,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResultsUiState())
    val uiState: StateFlow<ResultsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }

            val weekends = Graph.schedule.getSeason(season).getOrNull().orEmpty()
            val weekend = weekends.firstOrNull { it.round == round }
            val session = weekend?.sessions?.firstOrNull { it.kind == kind }
            if (weekend == null || session == null) {
                _uiState.update {
                    it.copy(loading = false, error = "This session is not in the $season calendar")
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    weekend = weekend,
                    session = session,
                    mapUrl = Graph.circuitMaps.cached(weekend),
                )
            }
            // A Wikipedia lookup only happens for the handful of circuits F1 has no map for.
            launch {
                val resolved = Graph.circuitMaps.resolve(weekend)
                if (resolved != null) _uiState.update { it.copy(mapUrl = resolved) }
            }

            Graph.sessionResults.getResults(weekend, session).fold(
                onSuccess = { results ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            state = results.state,
                            fastestLapRacingNumber = results.fastestLapRacingNumber,
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "Could not load these results",
                        )
                    }
                },
            )
        }
    }

    companion object {
        fun factory(season: Int, round: Int, kind: SessionKind): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras,
                ): T = ResultsViewModel(season, round, kind) as T
            }
    }
}
