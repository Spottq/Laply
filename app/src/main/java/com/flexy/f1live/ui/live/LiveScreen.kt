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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
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
import com.flexy.f1live.model.SessionBreak
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.model.TrackFlag
import com.flexy.f1live.model.WeekendWeather
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
import com.flexy.f1live.ui.components.RaceControlRow
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
    onOpenRaceControl: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isFollowing by viewModel.isFollowing.collectAsStateWithLifecycle()
    val nextSession by viewModel.nextSession.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val circuit by viewModel.circuit.collectAsStateWithLifecycle()
    val nextWeather by viewModel.nextWeather.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        viewModel.onScreenVisible()
        onDispose { viewModel.onScreenGone() }
    }

    LaunchedEffect(state.isLive) {
        if (!state.isLive) viewModel.refreshNextSessionIfStale()
    }

    val setFollowing = rememberFollowToggle()

    LiveContent(
        state = state,
        isFollowing = isFollowing,
        nextSession = nextSession,
        errorText = error,
        onRetry = viewModel::retry,
        onToggleFollow = { setFollowing(!isFollowing) },
        onOpenSchedule = onOpenSchedule,
        onOpenRaceControl = onOpenRaceControl,
        contentPadding = contentPadding,
        modifier = modifier,
        circuit = circuit,
        nextWeather = nextWeather,
    )
}

// ---------------------------------------------------------------- stateless content

private class PaneSpec(val margin: Dp, val gutter: Dp, val leftFraction: Float)

private val MediumPanes = PaneSpec(margin = 16.dp, gutter = 16.dp, leftFraction = 0.43f)

private val ExpandedPanes = PaneSpec(margin = 24.dp, gutter = 24.dp, leftFraction = 0.45f)

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
    onOpenRaceControl: (() -> Unit)? = null,
    circuit: CircuitOutline? = null,
    nextWeather: WeekendWeather = WeekendWeather.Loading,
) {
    val isRace = state.sessionKind == SessionKind.RACE || state.sessionKind == SessionKind.SPRINT
    var expandedNumber by rememberSaveable { mutableStateOf<String?>(null) }
    val onToggleRow: (String) -> Unit = { number ->
        expandedNumber = if (expandedNumber == number) null else number
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
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
                nextWeather = nextWeather,
                errorText = errorText,
                expandedNumber = expandedNumber,
                onToggleRow = onToggleRow,
                onRetry = onRetry,
                onToggleFollow = onToggleFollow,
                onOpenSchedule = onOpenSchedule,
                onOpenRaceControl = onOpenRaceControl,
                contentPadding = contentPadding,
            )
        } else {
            LiveTwoPanes(
                panes = panes,
                state = state,
                isRace = isRace,
                isFollowing = isFollowing,
                nextSession = nextSession,
                nextWeather = nextWeather,
                circuit = circuit,
                errorText = errorText,
                expandedNumber = expandedNumber,
                onToggleRow = onToggleRow,
                onRetry = onRetry,
                onToggleFollow = onToggleFollow,
                onOpenSchedule = onOpenSchedule,
                onOpenRaceControl = onOpenRaceControl,
                contentPadding = contentPadding,
            )
        }
    }
}

@Composable
private fun LiveSingleColumn(
    state: LiveSessionState,
    isRace: Boolean,
    isFollowing: Boolean,
    nextSession: UpcomingSession?,
    nextWeather: WeekendWeather,
    errorText: String?,
    expandedNumber: String?,
    onToggleRow: (String) -> Unit,
    onRetry: () -> Unit,
    onToggleFollow: () -> Unit,
    onOpenSchedule: (() -> Unit)?,
    onOpenRaceControl: (() -> Unit)?,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
    ) {
        headerSection(state, isRace, isFollowing, onToggleFollow, inPane = false)
        val nextRoundLast = state.isLive
        if (!nextRoundLast) {
            nextRoundSection(
                nextSession = nextSession,
                weather = nextWeather,
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
        raceControlSection(state, horizontal = CardHorizontal, onOpenAll = onOpenRaceControl)
        if (nextRoundLast) {
            nextRoundSection(
                nextSession = nextSession,
                weather = nextWeather,
                onOpenSchedule = onOpenSchedule,
                modifier = Modifier.padding(start = CardHorizontal, end = CardHorizontal, top = 20.dp),
            )
        }
        item(key = "tail", contentType = "spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LiveTwoPanes(
    panes: PaneSpec,
    state: LiveSessionState,
    isRace: Boolean,
    isFollowing: Boolean,
    nextSession: UpcomingSession?,
    nextWeather: WeekendWeather,
    circuit: CircuitOutline?,
    errorText: String?,
    expandedNumber: String?,
    onToggleRow: (String) -> Unit,
    onRetry: () -> Unit,
    onToggleFollow: () -> Unit,
    onOpenSchedule: (() -> Unit)?,
    onOpenRaceControl: (() -> Unit)?,
    contentPadding: PaddingValues,
) {
    val direction = LocalLayoutDirection.current
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
            circuitSection(state, circuit)
            conditionsSection(state)
            raceControlSection(state, horizontal = 0.dp, onOpenAll = onOpenRaceControl)
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
            nextRoundSection(
                nextSession = nextSession,
                weather = nextWeather,
                onOpenSchedule = onOpenSchedule,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- sections

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

private fun LazyListScope.nextRoundSection(
    nextSession: UpcomingSession?,
    weather: WeekendWeather,
    onOpenSchedule: (() -> Unit)?,
    modifier: Modifier,
    compact: Boolean = false,
) {
    if (nextSession == null) return
    item(key = "next-round", contentType = "next-round") {
        NextRoundCard(
            next = nextSession,
            onClick = onOpenSchedule,
            weather = weather,
            modifier = modifier,
            compact = compact,
        )
    }
}

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
        contentType = { "driver" },
    ) { driver ->
        DriverRow(
            driver = driver,
            isRace = isRace,
            fastestLap = isRace && driver.fastestLap,
            expanded = expandedNumber == driver.racingNumber,
            onClick = { onToggleRow(driver.racingNumber) },
            modifier = if (state.isLive) Modifier.animateItem() else Modifier,
            showPit = state.isLive,
            useBestSectors = !state.isLive,
            horizontalPadding = horizontal,
        )
    }
    item(key = "card-bottom", contentType = "card-bottom") {
        ClassificationCardBottom(horizontalPadding = horizontal)
    }
}

private fun LazyListScope.raceControlSection(
    state: LiveSessionState,
    horizontal: Dp,
    onOpenAll: (() -> Unit)?,
) {
    if (state.raceControl.isEmpty()) return
    item(key = "race-control", contentType = "race-control") {
        RaceControlSection(messages = state.raceControl, horizontal = horizontal, onOpenAll = onOpenAll)
    }
}

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
    inPane: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (inPane) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars),
                )
                .padding(horizontal = if (inPane) 0.dp else CardHorizontal)
                .padding(top = if (inPane) 20.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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

            DelayNotice(state)

            if (hasData) {
                Spacer(Modifier.height(20.dp))
                TopThreeRow(state.topThree, isRace)
            }

            val sessionOver = state.status == SessionStatus.FINISHED ||
                state.status == SessionStatus.FINALISED ||
                state.status == SessionStatus.ENDS
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

@Composable
private fun DelayNotice(state: LiveSessionState) {
    val label = SessionBreak.delayLabel(state) ?: return
    val message = SessionBreak.delayText(state).orEmpty()
    Spacer(Modifier.height(10.dp))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        if (message.isNotBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                textAlign = TextAlign.Center,
            )
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
        if (lap != null && total != null) parts += "Lap ${lap.coerceAtMost(total)}/$total"
        else if (lap != null) parts += "Lap $lap"
    } else {
        SessionBreak.partLabel(state)?.let { parts += it }
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

private val PodiumColumnPadding = 4.dp

private const val PodiumMinNameSp = 11f

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
private fun RaceControlSection(
    messages: List<RaceControlMessage>,
    horizontal: Dp,
    onOpenAll: (() -> Unit)?,
) {
    Column(modifier = Modifier.padding(horizontal = horizontal, vertical = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.race_control),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (onOpenAll != null) {
                TextButton(onClick = onOpenAll) {
                    Text(stringResource(R.string.race_control_all))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .then(if (onOpenAll != null) Modifier.clickable(onClick = onOpenAll) else Modifier)
                .padding(vertical = 4.dp),
        ) {
            messages.asReversed().take(RaceControlPreviewCount).forEach { message ->
                RaceControlRow(message)
            }
        }
    }
}

private const val RaceControlPreviewCount = 10

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
