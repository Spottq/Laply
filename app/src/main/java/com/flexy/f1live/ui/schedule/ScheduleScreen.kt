package com.flexy.f1live.ui.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flexy.f1live.model.HourForecast
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.ScheduledSession
import com.flexy.f1live.model.SessionForecast
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.WeekendForecast
import com.flexy.f1live.ui.SampleData
import com.flexy.f1live.ui.components.CenteredColumn
import com.flexy.f1live.ui.components.CountryFlag
import com.flexy.f1live.ui.components.RainChance
import com.flexy.f1live.ui.components.WeatherIcon
import com.flexy.f1live.ui.components.formatDate
import com.flexy.f1live.ui.components.formatDayTime
import com.flexy.f1live.ui.components.formatDegrees
import com.flexy.f1live.ui.components.formatTime
import com.flexy.f1live.ui.components.plusHorizontal
import com.flexy.f1live.ui.theme.F1LivePreviewTheme

@Composable
fun ScheduleScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: ScheduleViewModel = viewModel(),
    onOpenResults: (season: Int, round: Int, kind: SessionKind) -> Unit = { _, _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ScheduleContent(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onOpenResults = onOpenResults,
        contentPadding = contentPadding,
        modifier = modifier,
    )
}

@Composable
fun ScheduleContent(
    uiState: ScheduleUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenResults: (season: Int, round: Int, kind: SessionKind) -> Unit = { _, _, _ -> },
) {
    PullToRefreshBox(
        isRefreshing = uiState.refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            uiState.loading && uiState.isEmpty -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.error != null && uiState.isEmpty -> ErrorState(uiState.error, onRetry)

            else -> ScheduleList(uiState, contentPadding, onOpenResults)
        }
    }
}

@Composable
private fun ScheduleList(
    uiState: ScheduleUiState,
    contentPadding: PaddingValues,
    onOpenResults: (season: Int, round: Int, kind: SessionKind) -> Unit,
) {
    // Large screens: one centred column rather than cards stretched edge to edge.
    CenteredColumn { gutter ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding.plusHorizontal(gutter),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "title", contentType = "title") {
                Text(
                    text = uiState.season.toString() + " season",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 2.dp),
                )
            }

            if (uiState.error != null) {
                item(key = "inline-error", contentType = "label") {
                    Text(
                        text = uiState.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }

            items(uiState.upcoming, key = { "u" + it.round }, contentType = { "weekend" }) { weekend ->
                WeekendCard(
                    weekend = weekend,
                    highlighted = weekend.round == uiState.nextRound,
                    initiallyExpanded = weekend.round == uiState.nextRound,
                    past = false,
                    onOpenResults = onOpenResults,
                    forecast = uiState.forecasts[weekend.round],
                )
            }

            if (uiState.completed.isNotEmpty()) {
                item(key = "completed-header", contentType = "label") {
                    Text(
                        text = "Completed",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 2.dp),
                    )
                }
                items(uiState.completed, key = { "c" + it.round }, contentType = { "weekend" }) { weekend ->
                    WeekendCard(
                        weekend = weekend,
                        highlighted = false,
                        initiallyExpanded = false,
                        past = true,
                        onOpenResults = onOpenResults,
                    )
                }
            }

            item(key = "tail", contentType = "spacer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun WeekendCard(
    weekend: RaceWeekend,
    highlighted: Boolean,
    initiallyExpanded: Boolean,
    past: Boolean,
    onOpenResults: (season: Int, round: Int, kind: SessionKind) -> Unit,
    forecast: WeekendForecast? = null,
) {
    var expanded by rememberSaveable(weekend.round) { mutableStateOf(initiallyExpanded) }
    val colors = if (highlighted) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    }
    Card(
        colors = colors,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .alpha(if (past) 0.55f else 1f)
            // The current weekend stays open; the others fold with a tap.
            .then(if (highlighted) Modifier else Modifier.clickable { expanded = !expanded }),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = weekend.round.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(32.dp),
                )
                Spacer(Modifier.width(10.dp))
                CountryFlag(
                    countryCode = weekend.countryCode,
                    modifier = Modifier.size(width = 36.dp, height = 26.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = weekend.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = weekend.circuitName + " · " + weekend.locality,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (highlighted) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatDate(weekend.raceStartUtcMillis),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }

            AnimatedVisibility(
                visible = expanded || highlighted,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    val now = System.currentTimeMillis()
                    // The next session of the season gets the hours around its start.
                    val next = if (highlighted) weekend.sessions.firstOrNull { !isFinished(it, now) } else null
                    weekend.sessions.forEach { session ->
                        val finished = isFinished(session, now)
                        val start = session.startUtcMillis
                        val hours = if (session == next && forecast != null && start != null) {
                            forecast.hoursAround(start)
                        } else {
                            emptyList()
                        }
                        val openResults = { onOpenResults(weekend.season, weekend.round, session.kind) }
                        if (forecast == null) {
                            SessionRow(session = session, finished = finished, onClick = openResults)
                        } else {
                            WeatherSessionRow(
                                session = session,
                                finished = finished,
                                // What already ran has results instead.
                                forecast = forecast.sessions[session.kind]?.takeUnless { finished },
                                hours = hours,
                                onClick = openResults,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One line of an expanded weekend. A session that has already run opens its classification, so it
 * carries a chevron and a click target; a future one is plain text.
 */
@Composable
private fun SessionRow(session: ScheduledSession, finished: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (finished) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = session.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDayTime(session.startUtcMillis),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (finished) ResultsChevron()
    }
}

/**
 * [SessionRow] for the current weekend, the one with a forecast: the time moves under the name
 * to make room for the weather at the session's start, and the next session of the season gets the
 * hours around its start underneath.
 */
@Composable
private fun WeatherSessionRow(
    session: ScheduledSession,
    finished: Boolean,
    forecast: SessionForecast?,
    hours: List<HourForecast>,
    onClick: () -> Unit,
) {
    val secondary = LocalContentColor.current.copy(alpha = 0.72f)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (finished) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = formatDayTime(session.startUtcMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                )
            }
            if (forecast != null) {
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    forecast.rainChancePct?.let {
                        RainChance(it, Modifier.padding(end = 10.dp), quietColor = secondary)
                    }
                    WeatherIcon(code = forecast.weatherCode, isDay = forecast.isDay, size = 26.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = formatDegrees(forecast.tempC),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (finished) ResultsChevron()
        }
        if (hours.isNotEmpty()) {
            HoursStrip(hours, Modifier.padding(top = 2.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun ResultsChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = "Results",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp).size(18.dp),
    )
}

/** The mini card under the next session: the sky, temperature and rain chance hour by hour. */
@Composable
private fun HoursStrip(hours: List<HourForecast>, modifier: Modifier = Modifier) {
    val content = LocalContentColor.current
    val secondary = content.copy(alpha = 0.72f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(content.copy(alpha = 0.07f))
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        hours.forEach { hour ->
            Column(
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = formatTime(hour.utcMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = secondary,
                )
                Spacer(Modifier.height(4.dp))
                WeatherIcon(code = hour.weatherCode, isDay = hour.isDay, size = 30.dp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatDegrees(hour.tempC),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                hour.rainChancePct?.let {
                    RainChance(it, Modifier.padding(top = 2.dp), quietColor = secondary)
                }
            }
        }
    }
}

/** A session counts as run - and therefore openable - two hours after it starts. */
private const val SESSION_DONE_MILLIS = 2L * 60 * 60 * 1000

private fun isFinished(session: ScheduledSession, now: Long): Boolean {
    val start = session.startUtcMillis ?: return false
    return start + SESSION_DONE_MILLIS < now
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Could not load the schedule",
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

@Preview(name = "Schedule", showBackground = true, backgroundColor = 0xFF0E0E0F, heightDp = 700)
@Composable
private fun SchedulePreview() {
    F1LivePreviewTheme {
        ScheduleContent(
            uiState = ScheduleUiState(
                season = 2026,
                upcoming = listOf(SampleData.weekend, SampleData.weekend.copy(round = 17, name = "Azerbaijan Grand Prix", country = "Azerbaijan", locality = "Baku", circuitName = "Baku City Circuit", countryCode = "az")),
                completed = listOf(SampleData.weekend.copy(round = 15, name = "Dutch Grand Prix", country = "Netherlands", locality = "Zandvoort", circuitName = "Circuit Park Zandvoort", countryCode = "nl")),
                nextRound = 16,
            ),
            onRefresh = {},
            onRetry = {},
            contentPadding = PaddingValues(0.dp),
        )
    }
}
