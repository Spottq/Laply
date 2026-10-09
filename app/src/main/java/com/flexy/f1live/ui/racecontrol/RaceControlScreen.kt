package com.flexy.f1live.ui.racecontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flexy.f1live.R
import com.flexy.f1live.data.Graph
import com.flexy.f1live.live.LiveUpdateController
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.RaceControlMessage
import com.flexy.f1live.ui.SampleData
import com.flexy.f1live.ui.components.CenteredColumn
import com.flexy.f1live.ui.components.RaceControlRow
import com.flexy.f1live.ui.components.plusHorizontal
import com.flexy.f1live.ui.theme.F1LivePreviewTheme

@Composable
fun RaceControlScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        LiveUpdateController.attachUi()
        onDispose { LiveUpdateController.detachUi() }
    }
    val state by Graph.liveTiming.state.collectAsStateWithLifecycle()
    RaceControlContent(state = state, onBack = onBack, modifier = modifier)
}

@Composable
fun RaceControlContent(
    state: LiveSessionState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = state.raceControl
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.race_control),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        val subtitle = listOf(
                            state.meetingCountry.ifBlank { state.meetingName },
                            state.sessionName,
                            if (messages.isEmpty()) "" else stringResource(R.string.race_control_count, messages.size),
                        ).filter { it.isNotBlank() }.joinToString(" · ")
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
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.race_control_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        val newestFirst = messages.asReversed()
        CenteredColumn { gutter ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 4.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ).plusHorizontal(gutter),
                verticalArrangement = Arrangement.Top,
            ) {
                itemsIndexed(
                    items = newestFirst,
                    key = { index, message -> keyOf(message, newestFirst.size - index) },
                    contentType = { _, _ -> "message" },
                ) { index, message ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                    RaceControlRow(message, Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

private fun keyOf(message: RaceControlMessage, ordinalFromOldest: Int): String =
    ordinalFromOldest.toString() + "|" + (message.utcMillis ?: 0L)

@Preview(name = "Race control", showBackground = true, backgroundColor = 0xFF0E0E0F, heightDp = 700)
@Composable
private fun RaceControlPreview() {
    F1LivePreviewTheme {
        RaceControlContent(state = SampleData.liveState, onBack = {})
    }
}
