package com.flexy.f1live.ui.standings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.flexy.f1live.data.DriverHeadshots
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.flexy.f1live.R
import com.flexy.f1live.data.ConstructorStanding
import com.flexy.f1live.data.DriverStanding
import com.flexy.f1live.data.TeamLogos
import com.flexy.f1live.data.TitleFight
import com.flexy.f1live.data.constructorColorHex
import com.flexy.f1live.data.driverCountryCode
import com.flexy.f1live.ui.components.CenteredColumn
import com.flexy.f1live.ui.components.flagCdnUrl
import com.flexy.f1live.ui.components.plusHorizontal
import com.flexy.f1live.ui.components.rememberTeamColor
import com.flexy.f1live.ui.theme.F1LivePreviewTheme

@Composable
fun StandingsScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: StandingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    StandingsContent(
        uiState = uiState,
        onSelectTab = viewModel::selectTab,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        contentPadding = contentPadding,
        modifier = modifier,
    )
}

@Composable
fun StandingsContent(
    uiState: StandingsUiState,
    onSelectTab: (StandingsTab) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
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

            else -> StandingsList(uiState, onSelectTab, contentPadding)
        }
    }
}

@Composable
private fun StandingsList(
    uiState: StandingsUiState,
    onSelectTab: (StandingsTab) -> Unit,
    contentPadding: PaddingValues,
) {
    // Large screens: one centred column rather than rows stretched edge to edge.
    CenteredColumn { gutter ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding.plusHorizontal(gutter),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header", contentType = "header") {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                    Text(
                        text = uiState.season.toString() + " championship",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (uiState.round > 0) {
                            uiState.season.toString() + " · after round " + uiState.round
                        } else {
                            uiState.season.toString() + " season"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Each tab its own points left: a team scores with both its cars.
                    val titleSummary = when (uiState.tab) {
                        StandingsTab.Drivers -> uiState.titleFight?.summary
                        StandingsTab.Constructors -> uiState.constructorsFight?.summary
                    }
                    if (titleSummary != null) {
                        Text(
                            text = titleSummary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (uiState.fromCache) {
                        Text(
                            text = if (uiState.round > 0) {
                                "Offline · cached after round " + uiState.round
                            } else {
                                "Offline · showing the stored standings"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    TabSelector(uiState.tab, onSelectTab)
                }
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

            when (uiState.tab) {
                StandingsTab.Drivers -> {
                    // Everyone above the line can still win the title, nobody below it can.
                    val cut = uiState.titleCut ?: uiState.drivers.size
                    items(
                        items = uiState.drivers.take(cut),
                        key = { "d" + it.position + it.code },
                        contentType = { "driver" },
                    ) { driver ->
                        DriverStandingRow(driver, uiState.driverLeaderPoints)
                    }
                    if (cut < uiState.drivers.size) {
                        item(key = "title-cut", contentType = "title-cut") {
                            TitleCutLine(decided = uiState.titleFight?.decided == true)
                        }
                        items(
                            items = uiState.drivers.drop(cut),
                            key = { "d" + it.position + it.code },
                            contentType = { "driver" },
                        ) { driver ->
                            DriverStandingRow(driver, uiState.driverLeaderPoints)
                        }
                    }
                }

                StandingsTab.Constructors -> {
                    val cut = uiState.constructorsCut ?: uiState.constructors.size
                    items(
                        items = uiState.constructors.take(cut),
                        key = { "c" + it.position + it.constructorId },
                        contentType = { "constructor" },
                    ) { team ->
                        ConstructorStandingRow(team, uiState.constructorLeaderPoints)
                    }
                    if (cut < uiState.constructors.size) {
                        item(key = "constructors-title-cut", contentType = "title-cut") {
                            TitleCutLine(decided = uiState.constructorsFight?.decided == true)
                        }
                        items(
                            items = uiState.constructors.drop(cut),
                            key = { "c" + it.position + it.constructorId },
                            contentType = { "constructor" },
                        ) { team ->
                            ConstructorStandingRow(team, uiState.constructorLeaderPoints)
                        }
                    }
                }
            }

            item(key = "tail", contentType = "spacer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * Two-segment selector with a thumb that slides between the halves.
 *
 * Built by hand rather than with SingleChoiceSegmentedButtonRow: that control cross-fades its
 * selection, and the sliding thumb is the whole point here. The thumb is drawn first and the
 * labels on top of it, so a single spring animation moves the highlight without touching the text.
 */
@Composable
private fun TabSelector(selected: StandingsTab, onSelect: (StandingsTab) -> Unit) {
    val tabs = listOf(
        StandingsTab.Drivers to R.string.standings_drivers,
        StandingsTab.Constructors to R.string.standings_constructors,
    )
    val selectedIndex = tabs.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val offset by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "segment-thumb",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SegmentedHeight)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 2.dp,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth(1f / tabs.size)
                .fillMaxHeight()
                // The thumb is exactly one segment wide, so translating it by its own width lands
                // it on the next segment. Read in the draw phase: no relayout while it slides.
                .graphicsLayer { translationX = offset * size.width },
        ) {}

        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            tabs.forEach { (tab, labelRes) ->
                val isSelected = tab == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { if (!isSelected) onSelect(tab) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private val SegmentedHeight = 44.dp

// ------------------------------------------------------------------ rows

@Composable
private fun DriverStandingRow(driver: DriverStanding, leaderPoints: Double) {
    val accent = rememberTeamColor(constructorColorHex(driver.constructorId))
    val flagCode = remember(driver.code, driver.nationality) {
        driverCountryCode(driver.code, driver.nationality)
    }
    val headshot = remember(driver.code) { DriverHeadshots.forTla(driver.code) }
    StandingCard(position = driver.position) { onAccentSurface ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            PositionLabel(driver.position)
            Spacer(Modifier.width(8.dp))
            TeamDisc(
                label = driver.code,
                color = accent,
                size = 40.dp,
                countryCode = flagCode,
                headshotUrl = headshot,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = driver.shortName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = driver.constructorName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (onAccentSurface) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            PointsColumn(driver.points, driver.wins, onAccentSurface)
        }
        Spacer(Modifier.height(10.dp))
        GapBar(fraction = (driver.points / leaderPoints).toFloat(), color = accent)
    }
}

@Composable
private fun ConstructorStandingRow(team: ConstructorStanding, leaderPoints: Double) {
    val accent = rememberTeamColor(constructorColorHex(team.constructorId))
    StandingCard(position = team.position) { onAccentSurface ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            PositionLabel(team.position)
            Spacer(Modifier.width(8.dp))
            TeamLogoDisc(
                constructorId = team.constructorId,
                teamName = team.name,
                color = accent,
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = team.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            PointsColumn(team.points, team.wins, onAccentSurface)
        }
        Spacer(Modifier.height(10.dp))
        GapBar(fraction = (team.points / leaderPoints).toFloat(), color = accent)
    }
}

/**
 * Shared row shell: P1 gets the primaryContainer accent, the rest of the podium a slightly
 * raised surface, everyone else the flat container. `content` is told whether it is drawing on
 * the accent surface so it can pick the matching "on" colour.
 */
@Composable
private fun StandingCard(
    position: Int,
    content: @Composable (onAccentSurface: Boolean) -> Unit,
) {
    val leader = position == 1
    val podium = position in 2..3
    val colors = when {
        leader -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        podium -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )

        else -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    }
    Card(
        colors = colors,
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (leader || podium) 3.dp else 0.dp,
        ),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
    ) {
        // A standings card is a single item to a screen reader. Merging also collapses ~8
        // semantics nodes per row into one, which is what the per-frame accessibility tree walk
        // (`getAllUncoveredSemanticsNodesToIntObjectMap`) has to iterate on every scrolled frame.
        Column(
            modifier = Modifier
                .semantics(mergeDescendants = true) {}
                .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            content(leader)
        }
    }
}

@Composable
private fun PositionLabel(position: Int) {
    Text(
        text = position.toString(),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(30.dp),
    )
}

@Composable
private fun PointsColumn(points: Double, wins: Int, onAccentSurface: Boolean) {
    val muted = if (onAccentSurface) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = formatPoints(points),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "pts",
            style = MaterialTheme.typography.labelSmall,
            color = muted,
        )
        if (wins > 0) {
            Text(
                text = if (wins == 1) "1 win" else "$wins wins",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
    }
}

/**
 * Dashed rule under the last driver or team that can still win the title, captioned in the middle.
 * With one left above it the title is settled, and the caption says so.
 */
@Composable
private fun TitleCutLine(decided: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashedRule(color, Modifier.weight(1f))
        Text(
            text = if (decided) "Title decided" else "Title out of reach below",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
        DashedRule(color, Modifier.weight(1f))
    }
}

@Composable
private fun DashedRule(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.height(1.dp)) {
        val y = size.height / 2
        drawLine(
            color = color.copy(alpha = 0.6f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        )
    }
}

/** Thin proportional bar: how much of the leader's points this entry has. */
@Composable
private fun GapBar(fraction: Float, color: Color) {
    val safe = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(safe)
                .height(3.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.75f)),
        )
    }
}

/**
 * Team-coloured disc with the constructor logo inside, matching the driver discs next to it.
 *
 * Nothing is drawn under the logo: the earlier coloured bar showed through the transparent parts
 * of several marks (the Mercedes star, the Audi rings) as a stripe across the disc. When there is
 * no logo - an unknown constructor, or the CDN blocked behind a VPN exit - the tinted disc and its
 * coloured ring are the fallback on their own.
 */
@Composable
private fun TeamLogoDisc(constructorId: String, teamName: String, color: Color, size: Dp) {
    val logoUrl = remember(constructorId, teamName) {
        TeamLogos.forConstructorId(constructorId) ?: TeamLogos.forTeamName(teamName)
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.30f))
            .border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (logoUrl != null) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size).padding(6.dp),
            )
        }
    }
}

/** Team-coloured disc showing the driver's TLA, with a small flag badge. */
@Composable
private fun TeamDisc(
    label: String,
    color: Color,
    size: Dp,
    countryCode: String?,
    headshotUrl: String? = null,
) {
    val badge = (size * 0.42f).coerceAtLeast(12.dp)
    Box(modifier = Modifier.size(size + badge / 3)) {
        Box(
            modifier = Modifier
                .size(size)
                .align(Alignment.TopStart)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.30f))
                .border(2.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Read in the draw phase (graphicsLayer), so a loaded headshot fades the TLA out
            // without recomposing or re-measuring the row.
            val loaded = remember(headshotUrl) { mutableStateOf(false) }
            Text(
                text = label,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.30f).sp,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = if (loaded.value) 0f else 1f },
            )
            if (!headshotUrl.isNullOrBlank()) {
                AsyncImage(
                    model = headshotUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { loaded.value = true },
                    onError = { loaded.value = false },
                    modifier = Modifier.size(size).clip(CircleShape),
                )
            }
        }
        val flagUrl = flagCdnUrl(countryCode)
        if (flagUrl != null) {
            AsyncImage(
                model = flagUrl,
                // Decorative: the merged card already announces driver and team.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badge)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

/** "242" rather than "242.0", but "18.5" keeps its half point. */
private fun formatPoints(points: Double): String =
    if (points == points.toLong().toDouble()) points.toLong().toString() else points.toString()

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Could not load the standings",
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

@Preview(name = "Standings", showBackground = true, backgroundColor = 0xFF0E0E0F, heightDp = 760)
@Composable
private fun StandingsPreview() {
    F1LivePreviewTheme {
        StandingsContent(
            uiState = StandingsUiState(
                season = 2026,
                round = 12,
                drivers = listOf(
                    DriverStanding(1, 242.0, 5, "NOR", "4", "Lando", "Norris", "British", "McLaren", "mclaren"),
                    DriverStanding(2, 228.0, 4, "PIA", "81", "Oscar", "Piastri", "Australian", "McLaren", "mclaren"),
                    DriverStanding(3, 201.5, 3, "VER", "1", "Max", "Verstappen", "Dutch", "Red Bull", "red_bull"),
                    DriverStanding(4, 150.0, 0, "LEC", "16", "Charles", "Leclerc", "Monegasque", "Ferrari", "ferrari"),
                ),
                titleFight = TitleFight(racesLeft = 2, sprintsLeft = 1, contenders = 3),
                constructors = listOf(
                    ConstructorStanding(1, 470.0, 9, "McLaren", "mclaren", "British"),
                    ConstructorStanding(2, 280.0, 3, "Ferrari", "ferrari", "Italian"),
                ),
                constructorsFight = TitleFight(
                    racesLeft = 2,
                    sprintsLeft = 1,
                    contenders = 1,
                    perRace = TitleFight.RACE_ONE_TWO,
                    perSprint = TitleFight.SPRINT_ONE_TWO,
                ),
            ),
            onSelectTab = {},
            onRefresh = {},
            onRetry = {},
            contentPadding = PaddingValues(0.dp),
        )
    }
}
