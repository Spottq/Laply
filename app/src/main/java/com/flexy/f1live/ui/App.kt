package com.flexy.f1live.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.flexy.f1live.R
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.ui.live.LiveScreen
import com.flexy.f1live.ui.racecontrol.RaceControlScreen
import com.flexy.f1live.ui.results.ResultsScreen
import com.flexy.f1live.ui.schedule.ScheduleScreen
import com.flexy.f1live.ui.settings.SettingsScreen
import com.flexy.f1live.ui.standings.StandingsScreen
import kotlinx.coroutines.flow.first

private enum class Destination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Live("live", R.string.tab_live, Icons.Filled.Speed),
    Schedule("schedule", R.string.tab_schedule, Icons.Filled.CalendarMonth),
    Standings("standings", R.string.tab_standings, Icons.Filled.EmojiEvents),
}

private val ToolbarMargin = 16.dp

private const val ToolbarScrollThreshold = 2f

private const val SettingsRoute = "settings"

private const val RaceControlRoute = "race-control"

private const val ResultsRoute = "results/{season}/{round}/{kind}"

fun resultsRoute(season: Int, round: Int, kind: SessionKind): String =
    "results/" + season + "/" + round + "/" + kind.name

private fun NavBackStackEntry.isTopLevel(): Boolean =
    Destination.entries.any { it.route == destination.route }

@Composable
fun App(openLiveRequest: Int = 0, openStandingsRequest: Int = 0) {
    val navController = rememberNavController()
    LaunchedEffect(openLiveRequest) {
        if (openLiveRequest > 0) navController.popBackStack(Destination.Live.route, inclusive = false)
    }
    LaunchedEffect(openStandingsRequest) {
        if (openStandingsRequest > 0) {
            navController.currentBackStackEntryFlow.first()
            navController.navigate(Destination.Standings.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection),
            end = innerPadding.calculateEndPadding(layoutDirection),
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() +
                FloatingToolbarDefaults.ContainerSize + ToolbarMargin * 2,
        )

        var toolbarHeightPx by remember { mutableIntStateOf(0) }
        var toolbarHidden by remember { mutableStateOf(false) }
        val hideFraction by animateFloatAsState(
            targetValue = if (toolbarHidden) 1f else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "toolbar-hide",
        )
        val toolbarBottomPx = with(LocalDensity.current) {
            (innerPadding.calculateBottomPadding() + ToolbarMargin).roundToPx()
        }
        val toolbarScroll = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < -ToolbarScrollThreshold) toolbarHidden = true
                    if (available.y > ToolbarScrollThreshold) toolbarHidden = false
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (consumed.y == 0f && available.y > 0f) toolbarHidden = false
                    return Offset.Zero
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(toolbarScroll),
        ) {
            val fadeTop = innerPadding.calculateTopPadding()
            val fadeBottom = innerPadding.calculateBottomPadding() +
                FloatingToolbarDefaults.ContainerSize + ToolbarMargin * 2
            val screenBackground = MaterialTheme.colorScheme.background
            val motion = rememberNavMotion()
            val popFrom: (NavBackStackEntry) -> Unit = { entry ->
                if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack()
            }
            NavHost(
                navController = navController,
                startDestination = Destination.Live.route,
                enterTransition = {
                    motion.decide(initialState.isTopLevel(), targetState.isTopLevel(), pop = false)
                    EnterTransition.None
                },
                exitTransition = {
                    motion.decide(initialState.isTopLevel(), targetState.isTopLevel(), pop = false)
                    ExitTransition.KeepUntilTransitionsFinished
                },
                popEnterTransition = {
                    motion.decide(initialState.isTopLevel(), targetState.isTopLevel(), pop = true)
                    EnterTransition.None
                },
                popExitTransition = {
                    motion.decide(initialState.isTopLevel(), targetState.isTopLevel(), pop = true)
                    ExitTransition.KeepUntilTransitionsFinished
                },
                sizeTransform = null,
            ) {
                composable(Destination.Live.route) {
                    NavMotionScreen(motion, screenBackground, fadeTop, fadeBottom) {
                        LiveScreen(
                            contentPadding = contentPadding,
                            onOpenSchedule = {
                                navController.navigate(Destination.Schedule.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onOpenRaceControl = {
                                navController.navigate(RaceControlRoute) { launchSingleTop = true }
                            },
                        )
                    }
                }
                composable(RaceControlRoute) { entry ->
                    NavMotionScreen(motion, screenBackground, fadeTop, fadeBottom) {
                        RaceControlScreen(onBack = { popFrom(entry) })
                    }
                }
                composable(Destination.Schedule.route) {
                    NavMotionScreen(motion, screenBackground, fadeTop, fadeBottom) {
                        ScheduleScreen(
                            contentPadding = contentPadding,
                            onOpenResults = { season, round, kind ->
                                navController.navigate(resultsRoute(season, round, kind))
                            },
                        )
                    }
                }
                composable(Destination.Standings.route) {
                    NavMotionScreen(motion, screenBackground, fadeTop, fadeBottom) {
                        StandingsScreen(contentPadding = contentPadding)
                    }
                }
                composable(SettingsRoute) { entry ->
                    NavMotionScreen(motion, screenBackground, fadeTop, fadeBottom) {
                        SettingsScreen(onBack = { popFrom(entry) })
                    }
                }
                composable(
                    route = ResultsRoute,
                    arguments = listOf(
                        navArgument("season") { type = NavType.IntType },
                        navArgument("round") { type = NavType.IntType },
                        navArgument("kind") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val arguments = entry.arguments
                    NavMotionScreen(motion, screenBackground, fadeTop, fadeBottom) {
                        ResultsScreen(
                            season = arguments?.getInt("season") ?: 0,
                            round = arguments?.getInt("round") ?: 0,
                            kind = runCatching {
                                SessionKind.valueOf(arguments?.getString("kind").orEmpty())
                            }.getOrDefault(SessionKind.UNKNOWN),
                            onBack = { popFrom(entry) },
                        )
                    }
                }
            }

            val topLevel = Destination.entries.any { destination ->
                currentDestination?.hierarchy?.any { it.route == destination.route } == true
            }
            LaunchedEffect(topLevel) { if (topLevel) toolbarHidden = false }
            AnimatedVisibility(
                visible = topLevel,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = innerPadding.calculateBottomPadding() + ToolbarMargin),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    modifier = Modifier
                        .onSizeChanged { toolbarHeightPx = it.height }
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (hideFraction * (toolbarHeightPx + toolbarBottomPx))
                                    .roundToInt(),
                            )
                        },
                ) {
                    Destination.entries.forEach { destination ->
                        val selected =
                            currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        ToolbarNavItem(
                            destination = destination,
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                        )
                    }
                    VerticalDivider(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(horizontal = 4.dp)
                            .height(24.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    ToolbarIconItem(
                        icon = Icons.Filled.Settings,
                        label = stringResource(R.string.settings_title),
                        onClick = {
                            navController.navigate(SettingsRoute) { launchSingleTop = true }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarIconItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun RowScope.ToolbarNavItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(destination.labelRes)
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = label,
            tint = content,
            modifier = Modifier.size(22.dp),
        )
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
