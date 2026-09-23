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
import com.flexy.f1live.ui.results.ResultsScreen
import com.flexy.f1live.ui.schedule.ScheduleScreen
import com.flexy.f1live.ui.settings.SettingsScreen
import com.flexy.f1live.ui.standings.StandingsScreen

private enum class Destination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Live("live", R.string.tab_live, Icons.Filled.Speed),
    Schedule("schedule", R.string.tab_schedule, Icons.Filled.CalendarMonth),
    Standings("standings", R.string.tab_standings, Icons.Filled.EmojiEvents),
}

/** Gap between the floating toolbar and the navigation-bar inset below it. */
private val ToolbarMargin = 16.dp

/** Scroll deltas below this (in pixels per frame) are noise, not an intent to hide the toolbar. */
private const val ToolbarScrollThreshold = 2f

/** App settings: a detail screen reached from the toolbar's trailing gear, not a fourth tab. */
private const val SettingsRoute = "settings"

/** `results/{season}/{round}/{kind}` - a finished session opened from the schedule. */
private const val ResultsRoute = "results/{season}/{round}/{kind}"

fun resultsRoute(season: Int, round: Int, kind: SessionKind): String =
    "results/" + season + "/" + round + "/" + kind.name

private fun NavBackStackEntry.isTopLevel(): Boolean =
    Destination.entries.any { it.route == destination.route }

/**
 * [openLiveRequest] changes every time a home-screen widget is tapped while the app is already
 * open; the app then returns to the Live tab (a fresh launch starts there anyway).
 */
@Composable
fun App(openLiveRequest: Int = 0) {
    val navController = rememberNavController()
    LaunchedEffect(openLiveRequest) {
        if (openLiveRequest > 0) navController.popBackStack(Destination.Live.route, inclusive = false)
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        // The toolbar floats over the content, so the lists need to reserve room for it themselves.
        val contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection),
            end = innerPadding.calculateEndPadding(layoutDirection),
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() +
                FloatingToolbarDefaults.ContainerSize + ToolbarMargin * 2,
        )

        // The toolbar hides itself rather than using exitAlwaysScrollBehavior: that behavior
        // translates the bar by its own height only, so the part sitting in the bottom margin and
        // the navigation-bar inset stayed on screen. Here the travel is the full distance to the
        // bottom edge, measured from the bar itself.
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
                    // Dragging content up (negative y) means the user is reading downwards.
                    if (available.y < -ToolbarScrollThreshold) toolbarHidden = true
                    if (available.y > ToolbarScrollThreshold) toolbarHidden = false
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Nothing consumed while pulling down: the list is already at the top.
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
            // Only the screens are masked (each inside its own card, see NavMotionScreen): the
            // toolbar is a sibling of the NavHost, so it stays fully opaque while the rows behind
            // it dissolve.
            val fadeTop = innerPadding.calculateTopPadding()
            val fadeBottom = innerPadding.calculateBottomPadding() +
                FloatingToolbarDefaults.ContainerSize + ToolbarMargin * 2
            val screenBackground = MaterialTheme.colorScheme.background
            val motion = rememberNavMotion()
            // Pops one screen, but only from the screen that is actually on top: a second tap on
            // a back arrow while the first pop is still animating must not pop the tab below too.
            val popFrom: (NavBackStackEntry) -> Unit = { entry ->
                if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack()
            }
            NavHost(
                navController = navController,
                startDestination = Destination.Live.route,
                // The screens animate themselves (NavMotionScreen); these only pick the motion
                // and keep the outgoing screen composed until its own animation has finished.
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
                            // The next-round card opens the Schedule tab, exactly as its toolbar
                            // item would; the schedule already expands the next weekend.
                            onOpenSchedule = {
                                navController.navigate(Destination.Schedule.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
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

            // The toolbar belongs to the three top-level tabs; a detail screen owns its own bar.
            val topLevel = Destination.entries.any { destination ->
                currentDestination?.hierarchy?.any { it.route == destination.route } == true
            }
            // A detail screen's own scrolling reaches the same nested-scroll connection; coming
            // back to a tab must always bring the toolbar back rather than leave it parked below.
            LaunchedEffect(topLevel) { if (topLevel) toolbarHidden = false }
            // While a back gesture previews the tab under a detail screen the toolbar stays hidden:
            // it is not part of that preview, it only enters once the gesture commits and the
            // destination actually changes, so a cancelled gesture leaves it untouched.
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
                        // Its own height plus the margin and the navigation-bar inset below it:
                        // that is exactly the distance to the bottom edge of the display.
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
                    // Settings sit apart from the three tabs: a hairline, then an icon that opens
                    // a detail screen. It never becomes "selected" - the toolbar hides on it.
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

/** An icon-only toolbar action, sized like an unselected [ToolbarNavItem]. */
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

/**
 * One destination inside the floating toolbar: an icon that grows a pill with its label
 * once selected, so all three fit comfortably on a narrow phone.
 */
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
