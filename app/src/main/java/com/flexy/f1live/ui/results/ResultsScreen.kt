package com.flexy.f1live.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.ui.SampleData
import com.flexy.f1live.ui.components.CenteredColumn
import com.flexy.f1live.ui.components.CircuitOutline
import com.flexy.f1live.ui.components.CircuitOutlineCard
import com.flexy.f1live.ui.components.ClassificationCardBottom
import com.flexy.f1live.ui.components.ClassificationCardTop
import com.flexy.f1live.ui.components.ClassificationHorizontal
import com.flexy.f1live.ui.components.DriverRow
import com.flexy.f1live.ui.components.QualifyingLabels
import com.flexy.f1live.ui.components.SectorLabels
import com.flexy.f1live.ui.components.formatDate
import com.flexy.f1live.ui.components.plusHorizontal
import com.flexy.f1live.ui.theme.F1LivePreviewTheme

/**
 * A finished session opened from the schedule: track map on top, then the same classification the
 * Live screen draws. A detail screen, so it owns a back arrow and the toolbar stays hidden.
 */
@Composable
fun ResultsScreen(
    season: Int,
    round: Int,
    kind: SessionKind,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ResultsViewModel = viewModel(
        key = "results/$season/$round/$kind",
        factory = ResultsViewModel.factory(season, round, kind),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ResultsContent(
        uiState = uiState,
        onBack = onBack,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

@Composable
fun ResultsContent(
    uiState: ResultsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedNumber by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.title.ifBlank { "Results" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = subtitleOf(uiState)
                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        when {
            uiState.loading && uiState.drivers.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.error != null && uiState.drivers.isEmpty() -> ErrorState(
                message = uiState.error,
                onRetry = onRetry,
                contentPadding = innerPadding,
            )

            // Large screens: one centred column instead of rows stretched edge to edge.
            else -> CenteredColumn { gutter ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding() + 24.dp,
                    ).plusHorizontal(gutter),
                ) {
                    val weekend = uiState.weekend
                    if (weekend != null) {
                        item(key = "map", contentType = "map") {
                            // F1's clean outline; mapUrl only ever fills in for a circuit without one.
                            val circuit = remember(weekend, uiState.mapUrl) {
                                CircuitOutline.of(weekend, uiState.mapUrl)
                            }
                            CircuitOutlineCard(
                                circuit = circuit,
                                modifier = Modifier.padding(horizontal = ClassificationHorizontal),
                            )
                        }
                    }
                    item(key = "legend", contentType = "label") {
                        Text(
                            text = legendOf(uiState),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = ClassificationHorizontal + 4.dp,
                                end = ClassificationHorizontal,
                                top = 14.dp,
                                bottom = 8.dp,
                            ),
                        )
                    }
                    item(key = "card-top", contentType = "card-top") {
                        ClassificationCardTop(
                            title = uiState.session?.name.orEmpty(),
                            isRace = uiState.isRace,
                        )
                    }
                    items(
                        items = uiState.drivers,
                        key = { it.racingNumber },
                        contentType = { "driver" },
                    ) { driver ->
                        DriverRow(
                            driver = driver,
                            isRace = uiState.isRace,
                            expanded = expandedNumber == driver.racingNumber,
                            onClick = {
                                expandedNumber = if (expandedNumber == driver.racingNumber) {
                                    null
                                } else {
                                    driver.racingNumber
                                }
                            },
                            showPit = false,
                            fastestLap = driver.racingNumber == uiState.fastestLapRacingNumber,
                            sectorLabels = if (uiState.isQualifying) QualifyingLabels else SectorLabels,
                            useBestSectors = !uiState.isQualifying,
                        )
                    }
                    item(key = "card-bottom", contentType = "card-bottom") { ClassificationCardBottom() }
                }
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, contentPadding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No results yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

private fun subtitleOf(uiState: ResultsUiState): String {
    val session = uiState.session ?: return ""
    val date = formatDate(session.startUtcMillis)
    return session.name + " · " + date
}

/** "Race · 53 laps · winner 1:32:03.897" / "Qualifying · Q3". */
private fun legendOf(uiState: ResultsUiState): String {
    val session = uiState.session ?: return ""
    val parts = mutableListOf(session.name)
    if (uiState.isRace) {
        uiState.state?.totalLaps?.let { parts += it.toString() + " laps" }
        uiState.drivers.firstOrNull()?.lastLapTime?.takeIf { it.isNotBlank() }
            ?.let { parts += "winner " + it }
    } else if (uiState.isQualifying) {
        parts += "Q1 · Q2 · Q3"
    } else {
        parts += "best lap times"
    }
    return parts.joinToString(" · ")
}

@Preview(name = "Results", showBackground = true, backgroundColor = 0xFF0E0E0F, heightDp = 900)
@Composable
private fun ResultsPreview() {
    F1LivePreviewTheme {
        ResultsContent(
            uiState = ResultsUiState(
                loading = false,
                weekend = SampleData.weekend,
                session = SampleData.weekend.sessions.last(),
                state = SampleData.liveState,
                fastestLapRacingNumber = SampleData.liveState.drivers.firstOrNull()?.racingNumber,
                mapUrl = null,
            ),
            onBack = {},
            onRetry = {},
        )
    }
}
