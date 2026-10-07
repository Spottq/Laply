package com.flexy.f1live.ui.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flexy.f1live.R
import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.RaceControlMessage
import com.flexy.f1live.model.SectorTiming
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.model.TrackFlag
import com.flexy.f1live.ui.SampleData
import com.flexy.f1live.ui.components.CheckeredBackground
import com.flexy.f1live.ui.components.ClassificationCardBottom
import com.flexy.f1live.ui.components.ClassificationCardTop
import com.flexy.f1live.ui.components.DriverAvatar
import com.flexy.f1live.ui.components.DriverRow
import com.flexy.f1live.ui.components.ExpandedWidthBreakpoint
import com.flexy.f1live.ui.components.MediumWidthBreakpoint
import com.flexy.f1live.ui.components.MonoFamily
import com.flexy.f1live.ui.components.PitChip
import com.flexy.f1live.ui.components.CircuitOutline
import com.flexy.f1live.ui.components.CircuitOutlineCard
import com.flexy.f1live.ui.components.TyreDot
import com.flexy.f1live.ui.components.trackFlagColor
import com.flexy.f1live.ui.components.primaryTimeOf
import com.flexy.f1live.ui.components.rememberFollowToggle
import com.flexy.f1live.ui.components.flagColor
import com.flexy.f1live.ui.components.formatClock
import com.flexy.f1live.ui.components.statusLabel
import com.flexy.f1live.ui.components.rememberTeamColor
import com.flexy.f1live.ui.theme.F1LivePreviewTheme

private val CardHorizontal = 16.dp

// ---------------------------------------------------------------- stateful entry point

@Composable
fun LiveScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: LiveViewModel = viewModel(),
    onOpenSchedule: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isFollowing by viewModel.isFollowing.collectAsStateWithLifecycle()
    val nextSession by viewModel.nextSession.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val circuit by viewModel.circuit.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        viewModel.onScreenVisible()
        onDispose { viewModel.onScreenGone() }
    }

    // A session that just ended was the "next" one when the screen opened: pick a fresh one.
    LaunchedEffect(state.isLive) {
        if (!state.isLive) viewModel.refreshNextSessionIfStale()
    }

    // Same toggle as the Settings switch: permission first, then the opt-in.
    val setFollowing = rememberFollowToggle()

    LiveContent(
        state = state,
        isFollowing = isFollowing,
        nextSession = nextSession,
        errorText = error,
        onRetry = viewModel::retry,
        onToggleFollow = { setFollowing(!isFollowing) },
        onOpenSchedule = onOpenSchedule,
        contentPadding = contentPadding,
        modifier = modifier,
        circuit = circuit,
    )
}

// ---------------------------------------------------------------- stateless content

/**
 * Margins, the space between the panes and the left pane's share of the width - M3's canonical
 * "supporting pane" spacing for the window's width class.
 */
private class PaneSpec(val margin: Dp, val gutter: Dp, val leftFraction: Float)

/** 600-840dp: an unfolded foldable (the Fold7 inner screen is ~750dp), small tablets. */
private val MediumPanes = PaneSpec(margin = 16.dp, gutter = 16.dp, leftFraction = 0.43f)

/** 840dp and up: tablets in landscape. */
private val ExpandedPanes = PaneSpec(margin = 24.dp, gutter = 24.dp, leftFraction = 0.45f)

/** Below this the left pane switches the podium and the next-round card to their compact forms. */
private val CompactPaneWidth = 360.dp

@Composable
fun LiveContent(
    state: LiveSessionState,
    isFollowing: Boolean,
    nextSession: UpcomingSession?,
    errorText: String?,
    onRetry: () -> Unit,
    onToggleFollow: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenSchedule: (() -> Unit)? = null,
    /** Outline of the meeting's circuit; only the two-pane layout shows it. */
    circuit: CircuitOutline? = null,
) {
    val isRace = state.sessionKind == SessionKind.RACE || state.sessionKind == SessionKind.SPRINT
    var expandedNumber by rememberSaveable { mutableStateOf<String?>(null) }
    val onToggleRow: (String) -> Unit = { number ->
        expandedNumber = if (expandedNumber == number) null else number
    }

    // The width the screen actually gets (split screen and free-form windows included) picks the
    // layout: one column on phones, the header and the classification side by side from 600dp.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // One chequered backdrop for the whole screen, fixed while the lists scroll over it; the
        // cards keep their own surfaces on top. Its own layer, so a scroll only replays it.
        CheckeredBackground(
            modifier = Modifier.fillMaxSize().graphicsLayer(),
            fullScreen = true,
        )
        val panes = when {
            maxWidth >= ExpandedWidthBreakpoint -> ExpandedPanes
            maxWidth >= MediumWidthBreakpoint -> MediumPanes
            else -> null
        }
        if (panes == null) {
            LiveSingleColumn(
                state = state,
                isRace = isRace,
                isFollowing = isFollowing,
                nextSession = nextSession,
                errorText = errorText,
                expandedNumber = expandedNumber,
                onToggleRow = onToggleRow,
                onRetry = onRetry,
                onToggleFollow = onToggleFollow,
                onOpenSchedule = onOpenSchedule,
                contentPadding = contentPadding,
            )
        } else {
            val leftWidth = maxWidth * panes.leftFraction - panes.margin - panes.gutter / 2
            LiveTwoPanes(
                panes = panes,
                compact = leftWidth < CompactPaneWidth,
                state = state,
                isRace = isRace,
                isFollowing = isFollowing,
                nextSession = nextSession,
                circuit = circuit,
                errorText = errorText,
                expandedNumber = expandedNumber,
                onToggleRow = onToggleRow,
                onRetry = onRetry,
                onToggleFollow = onToggleFollow,
                onOpenSchedule = onOpenSchedule,
                contentPadding = contentPadding,
            )
        }
    }
}

/** Phones: header, next round, classification and race control in one list. */
@Composable
private fun LiveSingleColumn(
    state: LiveSessionState,
    isRace: Boolean,
    isFollowing: Boolean,
    nextSession: UpcomingSession?,
    errorText: String?,
    expandedNumber: String?,
    onToggleRow: (String) -> Unit,
    onRetry: () -> Unit,
    onToggleFollow: () -> Unit,
    onOpenSchedule: (() -> Unit)?,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Only the bottom inset: the checkered header draws full-bleed behind the status bar
        // and pads its own content with the top inset instead.
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
    ) {
        headerSection(state, isRace, isFollowing, onToggleFollow, inPane = false)
        // Between sessions the next round leads; while one runs it waits at the very bottom.
        val nextRoundLast = state.isLive
        if (!nextRoundLast) {
            nextRoundSection(
                nextSession = nextSession,
                onOpenSchedule = onOpenSchedule,
                modifier = Modifier.padding(
                    start = CardHorizontal,
                    end = CardHorizontal,
                    bottom = 20.dp,
                ),
            )
        }
        classificationSection(
            state = state,
            isRace = isRace,
            expandedNumber = expandedNumber,
            onToggleRow = onToggleRow,
            errorText = errorText,
            onRetry = onRetry,
            horizontal = CardHorizontal,
        )
        raceControlSection(state, horizontal = CardHorizontal)
        if (nextRoundLast) {
            nextRoundSection(
                nextSession = nextSession,
                onOpenSchedule = onOpenSchedule,
                modifier = Modifier.padding(start = CardHorizontal, end = CardHorizontal, top = 20.dp),
            )
        }
        item(key = "tail", contentType = "spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * Tablets and unfolded foldables: the header, the next round, the circuit and the conditions on
 * the left; the classification and race control on the right. Each pane scrolls on its own, so the
 * header stays put however long the classification is.
 */
@Composable
private fun LiveTwoPanes(
    panes: PaneSpec,
    compact: Boolean,
    state: LiveSessionState,
    isRace: Boolean,
    isFollowing: Boolean,
    nextSession: UpcomingSession?,
    circuit: CircuitOutline?,
    errorText: String?,
    expandedNumber: String?,
    onToggleRow: (String) -> Unit,
    onRetry: () -> Unit,
    onToggleFollow: () -> Unit,
    onOpenSchedule: (() -> Unit)?,
    contentPadding: PaddingValues,
) {
    val direction = LocalLayoutDirection.current
    // Both panes start below the status bar (the screen's edge fade dissolves them under it) and
    // end above the floating toolbar; the side insets only matter with a side navigation bar.
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp
    val bottom = contentPadding.calculateBottomPadding() + 24.dp
    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(panes.leftFraction).fillMaxHeight(),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(direction) + panes.margin,
                end = panes.gutter / 2,
                top = top,
                bottom = bottom,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            headerSection(state, isRace, isFollowing, onToggleFollow, inPane = true)
            // While a session runs the circuit and conditions are what matter, and the next round
            // waits at the bottom; between sessions, what comes next leads.
            if (state.isLive) {
                circuitSection(state, circuit)
                conditionsSection(state)
                nextRoundSection(nextSession, onOpenSchedule, Modifier, compact = compact)
            } else {
                nextRoundSection(nextSession, onOpenSchedule, Modifier, compact = compact)
                circuitSection(state, circuit)
                conditionsSection(state)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f - panes.leftFraction).fillMaxHeight(),
            contentPadding = PaddingValues(
                start = panes.gutter / 2,
                end = contentPadding.calculateEndPadding(direction) + panes.margin,
                top = top,
                bottom = bottom,
            ),
        ) {
            classificationSection(
                state = state,
                isRace = isRace,
                expandedNumber = expandedNumber,
                onToggleRow = onToggleRow,
                errorText = errorText,
                onRetry = onRetry,
                horizontal = 0.dp,
            )
            raceControlSection(state, horizontal = 0.dp)
        }
    }
}

// ---------------------------------------------------------------- sections

/** The checkered header: weather, headline, podium and the Follow call to action. */
private fun LazyListScope.headerSection(
    state: LiveSessionState,
    isRace: Boolean,
    isFollowing: Boolean,
    onToggleFollow: () -> Unit,
    inPane: Boolean,
) {
    item(key = "header", contentType = "header") {
        LiveHeader(
            state = state,
            isRace = isRace,
            isFollowing = isFollowing,
            hasData = state.drivers.isNotEmpty(),
            onToggleFollow = onToggleFollow,
            inPane = inPane,
        )
    }
}

/**
 * The weekend coming up. Callers place it: above the classification between sessions, at the
 * bottom (phone) or below the circuit (tablet) while one runs.
 */
private fun LazyListScope.nextRoundSection(
    nextSession: UpcomingSession?,
    onOpenSchedule: (() -> Unit)?,
    modifier: Modifier,
    compact: Boolean = false,
) {
    if (nextSession == null) return
    item(key = "next-round", contentType = "next-round") {
        NextRoundCard(
            next = nextSession,
            onClick = onOpenSchedule,
            modifier = modifier,
            compact = compact,
        )
    }
}

/** The classification card, or the empty state when there is nothing to classify. */
private fun LazyListScope.classificationSection(
    state: LiveSessionState,
    isRace: Boolean,
    expandedNumber: String?,
    onToggleRow: (String) -> Unit,
    errorText: String?,
    onRetry: () -> Unit,
    horizontal: Dp,
) {
    if (state.drivers.isEmpty()) {
        item(key = "empty", contentType = "empty") {
            EmptySessionCard(errorText = errorText, onRetry = onRetry, horizontal = horizontal)
        }
        return
    }
    item(key = "card-top", contentType = "card-top") {
        ClassificationCardTop(
            title = state.sessionName,
            isRace = isRace,
            horizontalPadding = horizontal,
        )
    }
    items(
        items = state.drivers,
        key = { it.racingNumber },
        // One content type for every row lets the lazy layout reuse a retired row's
        // subcomposition instead of building a fresh one on each fling.
        contentType = { "driver" },
    ) { driver ->
        DriverRow(
            driver = driver,
            isRace = isRace,
            // Only in races: elsewhere the tower is sorted by best lap, so the badge would
            // sit on P1 and say nothing.
            fastestLap = isRace && driver.fastestLap,
            expanded = expandedNumber == driver.racingNumber,
            onClick = { onToggleRow(driver.racingNumber) },
            // Placement animations only earn their per-item cost while positions actually
            // move; a finished session's classification is static.
            modifier = if (state.isLive) Modifier.animateItem() else Modifier,
            showPit = state.isLive,
            // A finished classification shows the best sectors of the session; while the
            // session runs the last lap is the interesting one.
            useBestSectors = !state.isLive,
            horizontalPadding = horizontal,
        )
    }
    item(key = "card-bottom", contentType = "card-bottom") {
        ClassificationCardBottom(horizontalPadding = horizontal)
    }
}

private fun LazyListScope.raceControlSection(state: LiveSessionState, horizontal: Dp) {
    if (state.raceControl.isEmpty()) return
    item(key = "race-control", contentType = "race-control") {
        RaceControlSection(messages = state.raceControl, horizontal = horizontal)
    }
}

/** Two-pane layout only: the circuit of the meeting on screen, when the calendar knows it. */
private fun LazyListScope.circuitSection(state: LiveSessionState, circuit: CircuitOutline?) {
    if (circuit == null || circuit.isEmpty) return
    item(key = "circuit", contentType = "circuit") {
        Column {
            val place = state.circuitShortName.ifBlank { state.meetingLocation }
            SectionLabel(
                stringResource(R.string.circuit) +
                    place.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            )
            CircuitOutlineCard(circuit = circuit)
        }
    }
}

/** Two-pane layout only: temperatures, track status, humidity and rain, whichever are reported. */
private fun LazyListScope.conditionsSection(state: LiveSessionState) {
    val showFlag = state.isLive && state.trackFlag != TrackFlag.UNKNOWN
    val hasAny = showFlag || state.airTempC != null || state.trackTempC != null ||
        state.humidityPct != null || state.rainfall != null
    if (!hasAny) return
    item(key = "conditions", contentType = "conditions") {
        ConditionsCard(state = state, showFlag = showFlag)
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun LiveHeader(
    state: LiveSessionState,
    isRace: Boolean,
    isFollowing: Boolean,
    hasData: Boolean,
    onToggleFollow: () -> Unit,
    /**
     * Inside a large-screen pane, which already starts below the status bar; on a phone the header
     * is the first thing under it and pads itself with the inset.
     */
    inPane: Boolean = false,
) {
    // Transparent over the screen's chequered backdrop (see LiveContent); only the podium sits on
    // a plate of its own.
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (inPane) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars),
                )
                // A pane already brings its margins; the header text starts a little below the
                // top of the classification card beside it.
                .padding(horizontal = if (inPane) 0.dp else CardHorizontal)
                .padding(top = if (inPane) 20.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Phone: keeps the height the share button used to give it, so the headline stays put
            // whether or not the feed reports temperatures. Pane: the temperatures live in the
            // Conditions card further down, so the headline starts level with the other pane.
            if (!inPane) Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WeatherChip(
                        icon = Icons.Filled.Thermostat,
                        value = state.airTempC,
                        caption = stringResource(R.string.air_temp),
                    )
                    WeatherChip(
                        icon = Icons.Filled.DeviceThermostat,
                        value = state.trackTempC,
                        caption = stringResource(R.string.track_temp),
                    )
                }
            }

            if (!inPane) Spacer(Modifier.height(12.dp))
            Text(
                text = "Formula 1" + state.meetingCountry.takeIf { it.isNotBlank() }
                    ?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = headlineFor(state, isRace, hasData),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (hasData) {
                Spacer(Modifier.height(20.dp))
                TopThreeRow(state.topThree, isRace)
            }

            val sessionOver = state.status == SessionStatus.FINISHED ||
                state.status == SessionStatus.FINALISED ||
                state.status == SessionStatus.ENDS
            // Follow is a one-way call to action: once the persistent opt-in ("auto-follow
            // sessions") is on, the button folds away. Turning it off lives in Settings.
            AnimatedVisibility(
                visible = !isFollowing,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(20.dp))
                    FollowButton(onClick = onToggleFollow)
                    val hint = when {
                        sessionOver && hasData -> R.string.session_over
                        !hasData && !state.isLive -> R.string.no_live_session
                        else -> null
                    }
                    if (hint != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun headlineFor(state: LiveSessionState, isRace: Boolean, hasData: Boolean): String {
    if (!hasData && state.sessionName.isBlank()) return "No live session"
    val parts = mutableListOf<String>()
    if (state.sessionName.isNotBlank()) parts += state.sessionName
    if (isRace) {
        val lap = state.currentLap
        val total = state.totalLaps
        // ESPN's lap counter is the lap being run, so a finished race reads one past the
        // distance ("Lap 57/56"); the leader can never be further than the flag.
        if (lap != null && total != null) parts += "Lap ${lap.coerceAtMost(total)}/$total"
        else if (lap != null) parts += "Lap $lap"
    } else {
        state.sessionPart?.let { parts += "Q$it" }
    }
    statusLabel(state.status).takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(" · ").ifBlank { "No live session" }
}

@Composable
private fun WeatherChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Double?,
    caption: String,
) {
    if (value == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                text = value.toInt().toString() + "°C",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Horizontal breathing room inside each podium column, so neighbouring names never touch. */
private val PodiumColumnPadding = 4.dp

/** The podium names never go below this; past it they ellipsize. */
private const val PodiumMinNameSp = 11f

/**
 * The top three as three equal columns: the same avatar size and centre line, and one shared name
 * size - the largest at which all three names fit their column - so no column ends up smaller or
 * taller than its neighbours however long a name is.
 */
@Composable
private fun TopThreeRow(drivers: List<DriverTiming>, isRace: Boolean) {
    val baseStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnPx = with(density) { (maxWidth / 3 - PodiumColumnPadding * 2).roundToPx() }
        val names = drivers.map { it.position.toString() + " " + it.shortName }
        val nameStyle = remember(names, columnPx, baseStyle) {
            var sizeSp = baseStyle.fontSize.value
            fun fits(sp: Float) = names.all { name ->
                measurer.measure(
                    text = name,
                    style = baseStyle.copy(fontSize = sp.sp),
                    maxLines = 1,
                    softWrap = false,
                ).size.width <= columnPx
            }
            while (sizeSp > PodiumMinNameSp && !fits(sizeSp)) sizeSp -= 0.5f
            // Only the glyphs shrink: the line height stays, so the row never changes height.
            baseStyle.copy(fontSize = sizeSp.sp)
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            drivers.forEach { driver ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f).padding(horizontal = PodiumColumnPadding),
                ) {
                    DriverAvatar(
                        driver = driver,
                        size = 64.dp,
                        ringWidth = 2.dp,
                        crossfade = true,
                        centered = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = driver.position.toString() + " " + driver.shortName,
                        style = nameStyle,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = primaryTimeOf(driver, isRace).ifBlank { "--" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // Fewer than three classified cars: keep the columns at a third each.
            repeat(3 - drivers.size.coerceAtMost(3)) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun FollowButton(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Icon(
            Icons.Filled.NotificationsActive,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.follow), fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- classification

// ---------------------------------------------------------------- empty + race control

@Composable
private fun EmptySessionCard(errorText: String?, onRetry: () -> Unit, horizontal: Dp) {
    Column(
        modifier = Modifier
            .padding(horizontal = horizontal)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.no_live_session),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Timing appears here once a session goes live.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Only set when F1 and ESPN are both down and there is nothing, not even a cached
        // session, to show (see CompositeLiveTimingClient); the details are in logcat.
        if (errorText != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.live_timing_unreachable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry) { Text("Reconnect") }
        }
    }
}

@Composable
private fun RaceControlSection(messages: List<RaceControlMessage>, horizontal: Dp) {
    Column(modifier = Modifier.padding(horizontal = horizontal, vertical = 16.dp)) {
        Text(
            text = "Race control",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(vertical = 4.dp),
        ) {
            messages.asReversed().take(10).forEach { message ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(8.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(flagColor(message.flag)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = message.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val stamp = formatClock(message.utcMillis)
                        if (stamp.isNotBlank()) {
                            Text(
                                text = stamp,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- large-screen extras

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

/**
 * Air and track temperature, track status (live only), humidity and rain. Wraps onto a second line
 * in a narrow pane rather than squeezing the chips.
 */
@Composable
private fun ConditionsCard(state: LiveSessionState, showFlag: Boolean) {
    Column {
        SectionLabel(stringResource(R.string.conditions))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.airTempC?.let { air ->
                InfoChip(
                    icon = Icons.Filled.Thermostat,
                    value = air.toInt().toString() + "°C",
                    caption = stringResource(R.string.air_temp),
                )
            }
            state.trackTempC?.let { track ->
                InfoChip(
                    icon = Icons.Filled.DeviceThermostat,
                    value = track.toInt().toString() + "°C",
                    caption = stringResource(R.string.track_temp),
                )
            }
            if (showFlag) {
                InfoChip(
                    icon = Icons.Filled.Flag,
                    tint = trackFlagColor(state.trackFlag),
                    value = trackFlagLabel(state.trackFlag),
                    caption = stringResource(R.string.track_status),
                )
            }
            state.humidityPct?.let { humidity ->
                InfoChip(
                    icon = Icons.Filled.WaterDrop,
                    value = humidity.toInt().toString() + "%",
                    caption = stringResource(R.string.humidity),
                )
            }
            state.rainfall?.let { wet ->
                InfoChip(
                    icon = Icons.Filled.Umbrella,
                    value = stringResource(if (wet) R.string.rain_wet else R.string.rain_dry),
                    caption = stringResource(R.string.rainfall),
                )
            }
        }
    }
}

private fun trackFlagLabel(flag: TrackFlag): String = when (flag) {
    TrackFlag.GREEN -> "Green"
    TrackFlag.YELLOW -> "Yellow"
    TrackFlag.RED -> "Red"
    TrackFlag.SC -> "Safety car"
    TrackFlag.VSC -> "VSC"
    TrackFlag.VSC_ENDING -> "VSC ending"
    TrackFlag.UNKNOWN -> "--"
}

/** Icon, value and caption: the header's weather chip for any value. */
@Composable
private fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    caption: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------- previews

@Preview(name = "Live screen", showBackground = true, backgroundColor = 0xFF0E0E0F, heightDp = 1400)
@Composable
private fun LiveScreenPreview() {
    F1LivePreviewTheme {
        LiveContent(
            state = SampleData.liveState,
            isFollowing = false,
            nextSession = null,
            errorText = null,
            onRetry = {},
            onToggleFollow = {},
            contentPadding = PaddingValues(0.dp),
        )
    }
}

@Preview(name = "Live screen (finished)", showBackground = true, backgroundColor = 0xFF0E0E0F, heightDp = 1000)
@Composable
private fun LiveScreenFinishedPreview() {
    F1LivePreviewTheme {
        LiveContent(
            state = SampleData.liveState.copy(status = SessionStatus.FINISHED, sessionPart = null),
            isFollowing = true,
            nextSession = UpcomingSession(
                SampleData.weekend.copy(
                    round = 17, name = "Azerbaijan Grand Prix", country = "Azerbaijan",
                    locality = "Baku", circuitName = "Baku City Circuit", countryCode = "az",
                ),
                SampleData.weekend.sessions.first(),
            ),
            errorText = null,
            onRetry = {},
            onToggleFollow = {},
            contentPadding = PaddingValues(0.dp),
        )
    }
}

@Preview(name = "Live screen (empty)", showBackground = true, backgroundColor = 0xFF0E0E0F)
@Composable
private fun LiveScreenEmptyPreview() {
    F1LivePreviewTheme {
        LiveContent(
            state = LiveSessionState.EMPTY.copy(status = SessionStatus.INACTIVE),
            isFollowing = false,
            nextSession = UpcomingSession(SampleData.weekend, SampleData.weekend.sessions.last()),
            errorText = null,
            onRetry = {},
            onToggleFollow = {},
            contentPadding = PaddingValues(0.dp),
        )
    }
}

private val PreviewFinishedState = SampleData.liveState.copy(
    status = SessionStatus.FINISHED,
    sessionPart = null,
)

private val PreviewNextSession = UpcomingSession(
    SampleData.weekend.copy(
        round = 17, name = "Azerbaijan Grand Prix", country = "Azerbaijan",
        locality = "Baku", circuitName = "Baku City Circuit", countryCode = "az",
    ),
    SampleData.weekend.sessions.first(),
)

@Preview(
    name = "Live tablet",
    device = "spec:width=1280dp,height=800dp,dpi=240",
    showBackground = true,
    backgroundColor = 0xFF0E0E0F,
)
@Composable
private fun LiveTabletPreview() {
    F1LivePreviewTheme {
        LiveContent(
            state = SampleData.liveState,
            isFollowing = false,
            nextSession = null,
            errorText = null,
            onRetry = {},
            onToggleFollow = {},
            contentPadding = PaddingValues(bottom = 96.dp),
            circuit = CircuitOutline.of(SampleData.weekend),
        )
    }
}

@Preview(
    name = "Live tablet (finished)",
    device = "spec:width=1280dp,height=800dp,dpi=240",
    showBackground = true,
    backgroundColor = 0xFF0E0E0F,
)
@Composable
private fun LiveTabletFinishedPreview() {
    F1LivePreviewTheme {
        LiveContent(
            state = PreviewFinishedState,
            isFollowing = false,
            nextSession = PreviewNextSession,
            errorText = null,
            onRetry = {},
            onToggleFollow = {},
            contentPadding = PaddingValues(bottom = 96.dp),
            circuit = CircuitOutline.of(SampleData.weekend),
        )
    }
}

@Preview(
    name = "Live foldable (finished)",
    device = "spec:width=750dp,height=832dp,dpi=420",
    showBackground = true,
    backgroundColor = 0xFF0E0E0F,
)
@Composable
private fun LiveFoldableFinishedPreview() {
    F1LivePreviewTheme {
        LiveContent(
            state = PreviewFinishedState,
            isFollowing = false,
            nextSession = PreviewNextSession,
            errorText = null,
            onRetry = {},
            onToggleFollow = {},
            contentPadding = PaddingValues(bottom = 96.dp),
            circuit = CircuitOutline.of(SampleData.weekend),
        )
    }
}

@Preview(name = "Driver row", showBackground = true, backgroundColor = 0xFF1B1B1C, widthDp = 400)
@Composable
private fun DriverRowPreview() {
    F1LivePreviewTheme {
        Column {
            DriverRow(SampleData.drivers[0], isRace = false, expanded = true, onClick = {})
            DriverRow(SampleData.drivers[4], isRace = false, expanded = false, onClick = {})
            DriverRow(SampleData.drivers[7], isRace = false, expanded = false, onClick = {})
        }
    }
}
