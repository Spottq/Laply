package com.flexy.f1live.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flexy.f1live.R
import com.flexy.f1live.data.Graph
import com.flexy.f1live.live.LiveUpdateService
import com.flexy.f1live.settings.AppSettings
import com.flexy.f1live.settings.ThemeMode
import com.flexy.f1live.ui.components.CenteredColumn
import com.flexy.f1live.ui.components.plusHorizontal
import com.flexy.f1live.ui.components.rememberFollowToggle
import com.flexy.f1live.ui.theme.F1LivePreviewTheme
import com.flexy.f1live.update.UpdateChecker
import com.flexy.f1live.widget.OneUi
import kotlinx.coroutines.launch

/** Everything the Settings screen shows, so [SettingsContent] stays previewable. */
data class SettingsUiState(
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val autoLiveUpdate: Boolean,
    val metricStyle: Boolean,
    val metricStyleSupported: Boolean,
    val version: String,
    val widgetTrackBackground: Boolean = true,
    val checkUpdates: Boolean = true,
    val checkingForUpdate: Boolean = false,
)

/**
 * App settings. A detail screen like the session results: it owns a back arrow and the floating
 * toolbar stays hidden while it is open.
 *
 * No ViewModel: every value is a process-wide [AppSettings] StateFlow, which already survives
 * configuration changes and is shared with the theme, the Live tab and the Live Update service.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val themeMode by AppSettings.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by AppSettings.dynamicColor.collectAsStateWithLifecycle()
    val autoLiveUpdate by AppSettings.autoLiveUpdate.collectAsStateWithLifecycle()
    val metricStyle by AppSettings.metricStyle.collectAsStateWithLifecycle()
    val widgetTrackBackground by AppSettings.widgetTrackBackground.collectAsStateWithLifecycle()
    val checkUpdates by AppSettings.checkUpdates.collectAsStateWithLifecycle()
    val version = remember(context) { UpdateChecker.installedVersion(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    // The very same toggle as the Follow button: permission, alarm and service all included.
    val setAutoLiveUpdate = rememberFollowToggle()

    SettingsContent(
        state = SettingsUiState(
            themeMode = themeMode,
            dynamicColor = dynamicColor,
            autoLiveUpdate = autoLiveUpdate,
            metricStyle = metricStyle,
            metricStyleSupported = AppSettings.metricStyleSupported,
            version = version,
            widgetTrackBackground = widgetTrackBackground,
            checkUpdates = checkUpdates,
            checkingForUpdate = checking,
        ),
        onBack = onBack,
        onThemeMode = { AppSettings.setThemeMode(context, it) },
        onDynamicColor = { AppSettings.setDynamicColor(context, it) },
        onAutoLiveUpdate = setAutoLiveUpdate,
        onMetricStyle = { AppSettings.setMetricStyle(context, it) },
        // The demo race is a developer tool: hidden in release builds, kept in debug ones.
        onTestLiveUpdate = if (isDebuggable(context)) {
            { LiveUpdateService.startDemo(context) }
        } else {
            null
        },
        // AppSettings is observed by WidgetUpdater, which redraws every placed widget.
        onWidgetTrackBackground = { AppSettings.setWidgetTrackBackground(context, it) },
        // F1App observes the setting and schedules or cancels the daily UpdateCheckWorker.
        onCheckUpdates = { AppSettings.setCheckUpdates(context, it) },
        onCheckNow = {
            if (!checking) {
                checking = true
                scope.launch {
                    val result = UpdateChecker.check(context, Graph.http)
                    checking = false
                    snackbarHostState.currentSnackbarData?.dismiss()
                    when (result) {
                        is UpdateChecker.Result.Available -> {
                            val shown = snackbarHostState.showSnackbar(
                                message = context.getString(
                                    R.string.update_available,
                                    result.release.version,
                                ),
                                actionLabel = context.getString(R.string.update_download),
                                duration = SnackbarDuration.Long,
                            )
                            if (shown == SnackbarResult.ActionPerformed) {
                                runCatching {
                                    context.startActivity(UpdateChecker.downloadIntent(result.release))
                                }
                            }
                        }
                        is UpdateChecker.Result.UpToDate -> snackbarHostState.showSnackbar(
                            context.getString(R.string.update_up_to_date),
                        )
                        is UpdateChecker.Result.Failed -> snackbarHostState.showSnackbar(
                            context.getString(R.string.update_check_failed),
                        )
                    }
                }
            }
        },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

private fun isDebuggable(context: Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

@Composable
fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onAutoLiveUpdate: (Boolean) -> Unit,
    onMetricStyle: (Boolean) -> Unit,
    onTestLiveUpdate: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onWidgetTrackBackground: (Boolean) -> Unit = {},
    onCheckUpdates: (Boolean) -> Unit = {},
    onCheckNow: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        // Large screens: one centred column instead of rows stretched edge to edge.
        CenteredColumn { gutter ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ).plusHorizontal(gutter),
            ) {
                item(key = "appearance") {
                    SettingsGroup(R.string.settings_section_appearance) {
                        ThemePicker(
                            selected = state.themeMode,
                            onSelect = onThemeMode,
                            index = 0,
                            count = 2,
                        )
                        SwitchItem(
                            index = 1,
                            count = 2,
                            icon = Icons.Filled.Palette,
                            title = stringResource(R.string.settings_dynamic_color),
                            description = stringResource(R.string.settings_dynamic_color_desc),
                            checked = state.dynamicColor,
                            onCheckedChange = onDynamicColor,
                        )
                    }
                }
                item(key = "live") {
                    val liveCount = if (onTestLiveUpdate != null) 3 else 2
                    SettingsGroup(R.string.settings_section_live) {
                        SwitchItem(
                            index = 0,
                            count = liveCount,
                            icon = Icons.Filled.NotificationsActive,
                            title = stringResource(R.string.settings_auto_live),
                            description = stringResource(R.string.settings_auto_live_desc),
                            checked = state.autoLiveUpdate,
                            onCheckedChange = onAutoLiveUpdate,
                        )
                        SwitchItem(
                            index = 1,
                            count = liveCount,
                            icon = Icons.Filled.SpaceDashboard,
                            title = stringResource(R.string.settings_metric_style),
                            description = stringResource(
                                if (state.metricStyleSupported) {
                                    R.string.settings_metric_style_desc
                                } else {
                                    R.string.settings_metric_style_unsupported
                                },
                            ),
                            // Below Android 17 the switch cannot do anything: shown off and disabled.
                            checked = state.metricStyle && state.metricStyleSupported,
                            enabled = state.metricStyleSupported,
                            onCheckedChange = onMetricStyle,
                        )
                        if (onTestLiveUpdate != null) {
                            ActionItem(
                                index = 2,
                                count = liveCount,
                                icon = Icons.Filled.PlayCircle,
                                title = stringResource(R.string.settings_test_live),
                                description = stringResource(R.string.settings_test_live_desc),
                                onClick = onTestLiveUpdate,
                            )
                        }
                    }
                }
                item(key = "widgets") {
                    // The One UI glass options live in the widget's own settings (its long-press
                    // "Settings" / "Customize"): One UI passes widgets no transparency of its own.
                    WidgetSettingsGroup(
                        trackBackground = state.widgetTrackBackground,
                        oneUiBlurSupported = false,
                        samsungBlur = false,
                        blurAlpha = AppSettings.WIDGET_BLUR_ALPHAS[2],
                        tone = ThemeMode.SYSTEM,
                        onTrackBackground = onWidgetTrackBackground,
                        onSamsungBlur = {},
                        onBlurAlpha = {},
                        onTone = {},
                    )
                }
                item(key = "about") {
                    val aboutCount = if (onCheckNow != null) 3 else 2
                    SettingsGroup(R.string.settings_section_about) {
                        InfoItem(
                            index = 0,
                            count = aboutCount,
                            icon = Icons.Filled.Info,
                            title = stringResource(R.string.settings_version),
                            value = state.version,
                        )
                        SwitchItem(
                            index = 1,
                            count = aboutCount,
                            icon = Icons.Filled.Update,
                            title = stringResource(R.string.settings_check_updates),
                            description = stringResource(R.string.settings_check_updates_desc),
                            checked = state.checkUpdates,
                            onCheckedChange = onCheckUpdates,
                        )
                        if (onCheckNow != null) {
                            ActionItem(
                                index = 2,
                                count = aboutCount,
                                icon = Icons.Filled.SystemUpdate,
                                title = stringResource(R.string.settings_check_now),
                                description = stringResource(
                                    if (state.checkingForUpdate) {
                                        R.string.settings_checking
                                    } else {
                                        R.string.settings_check_now_desc
                                    },
                                ),
                                onClick = onCheckNow,
                                enabled = !state.checkingForUpdate,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The widget appearance options. The widget's own settings screen (WidgetConfigActivity, from
 * its long-press menu) shows them all: circuit background, and on One UI 7+ the blur style with
 * its opacity and tone. Settings > Widgets shows only the circuit background.
 */
@Composable
internal fun WidgetSettingsGroup(
    trackBackground: Boolean,
    oneUiBlurSupported: Boolean,
    samsungBlur: Boolean,
    blurAlpha: Int,
    tone: ThemeMode,
    onTrackBackground: (Boolean) -> Unit,
    onSamsungBlur: (Boolean) -> Unit,
    onBlurAlpha: (Int) -> Unit,
    onTone: (ThemeMode) -> Unit,
) {
    // The One UI rows only exist on One UI 7+; opacity and tone only mean something
    // while the blur is on.
    val oneUi = oneUiBlurSupported
    val glassRows = oneUi && samsungBlur
    val count = 1 + (if (oneUi) 1 else 0) + (if (glassRows) 2 else 0)
    SettingsGroup(R.string.settings_section_widgets) {
        SwitchItem(
            index = 0,
            count = count,
            icon = Icons.Filled.Widgets,
            title = stringResource(R.string.settings_widget_track_background),
            description = stringResource(R.string.settings_widget_track_background_desc),
            checked = trackBackground,
            onCheckedChange = onTrackBackground,
        )
        if (oneUi) {
            SwitchItem(
                index = 1,
                count = count,
                icon = Icons.Filled.BlurOn,
                title = stringResource(R.string.settings_widget_samsung_blur),
                description = stringResource(R.string.settings_widget_samsung_blur_desc),
                checked = samsungBlur,
                onCheckedChange = onSamsungBlur,
            )
        }
        if (glassRows) {
            val alphas = AppSettings.WIDGET_BLUR_ALPHAS
            ChoiceItem(
                index = 2,
                count = count,
                icon = Icons.Filled.Opacity,
                title = stringResource(R.string.settings_widget_opacity),
                options = listOf(
                    R.string.settings_widget_opacity_glass,
                    R.string.settings_widget_opacity_light,
                    R.string.settings_widget_opacity_medium,
                    R.string.settings_widget_opacity_solid,
                ).map { stringResource(it) to null },
                selected = alphas.indexOf(blurAlpha).coerceAtLeast(0),
                onSelect = { onBlurAlpha(alphas[it]) },
            )
            val tones = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
            ChoiceItem(
                index = 3,
                count = count,
                icon = Icons.Filled.Contrast,
                title = stringResource(R.string.settings_widget_tone),
                options = listOf(
                    stringResource(R.string.settings_theme_system) to Icons.Filled.BrightnessAuto,
                    stringResource(R.string.settings_theme_light) to Icons.Filled.LightMode,
                    stringResource(R.string.settings_theme_dark) to Icons.Filled.DarkMode,
                ),
                selected = tones.indexOf(tone).coerceAtLeast(0),
                onSelect = { onTone(tones[it]) },
            )
        }
    }
}

/** [WidgetSettingsGroup] bound to [AppSettings]: every change redraws the placed widgets. */
@Composable
internal fun WidgetSettingsGroup() {
    val context = LocalContext.current
    val trackBackground by AppSettings.widgetTrackBackground.collectAsStateWithLifecycle()
    val samsungBlur by AppSettings.widgetSamsungBlur.collectAsStateWithLifecycle()
    val blurAlpha by AppSettings.widgetBlurAlpha.collectAsStateWithLifecycle()
    val tone by AppSettings.widgetTone.collectAsStateWithLifecycle()
    val oneUiBlurSupported = remember(context) { OneUi.supportsHomeBlur(context) }
    WidgetSettingsGroup(
        trackBackground = trackBackground,
        oneUiBlurSupported = oneUiBlurSupported,
        samsungBlur = samsungBlur,
        blurAlpha = blurAlpha,
        tone = tone,
        onTrackBackground = { AppSettings.setWidgetTrackBackground(context, it) },
        onSamsungBlur = { AppSettings.setWidgetSamsungBlur(context, it) },
        onBlurAlpha = { AppSettings.setWidgetBlurAlpha(context, it) },
        onTone = { AppSettings.setWidgetTone(context, it) },
    )
}

// ---------------------------------------------------------------- building blocks

/**
 * A titled group of items drawn as one Expressive segmented list: rounded outer corners, tight
 * inner corners and a hairline gap between the items.
 */
@Composable
private fun SettingsGroup(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            content = content,
        )
    }
}

/**
 * A setting that is a plain on/off. The whole row toggles; the switch only mirrors the state
 * (`onCheckedChange = null`), so there is one touch target and one ripple, and the row carries
 * the switch semantics for TalkBack.
 */
@Composable
private fun SwitchItem(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        enabled = enabled,
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        supportingContent = description?.let { text -> { Text(text) } },
        content = { Text(title) },
    )
}

/** A row that does something once when tapped. */
@Composable
private fun ActionItem(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    description: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        enabled = enabled,
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = description?.let { text -> { Text(text) } },
        content = { Text(title) },
    )
}

/** A read-only row: label on the left, value in the supporting line. */
@Composable
private fun InfoItem(index: Int, count: Int, icon: ImageVector, title: String, value: String) {
    SegmentedListItem(
        onClick = {},
        enabled = false,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        // Disabled only to drop the ripple; keep the enabled colours so it does not look greyed.
        colors = ListItemDefaults.segmentedColors().let { colors ->
            ListItemDefaults.segmentedColors(
                disabledContainerColor = colors.containerColor,
                disabledContentColor = colors.contentColor,
                disabledLeadingContentColor = colors.leadingContentColor,
                disabledSupportingContentColor = colors.supportingContentColor,
            )
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = { Text(value.ifBlank { "-" }) },
        content = { Text(title) },
    )
}

/**
 * System / Light / Dark as an Expressive connected button group inside a list-shaped card, so it
 * reads as one item of the Appearance group.
 */
@Composable
private fun ThemePicker(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    index: Int,
    count: Int,
) {
    val shape = ListItemDefaults.segmentedShapes(index, count).shape
    val colors = ListItemDefaults.segmentedColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.containerColor)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                tint = colors.leadingContentColor,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.contentColor,
            )
        }
        Spacer(Modifier.height(12.dp))
        val options = listOf(
            Triple(ThemeMode.SYSTEM, R.string.settings_theme_system, Icons.Filled.BrightnessAuto),
            Triple(ThemeMode.LIGHT, R.string.settings_theme_light, Icons.Filled.LightMode),
            Triple(ThemeMode.DARK, R.string.settings_theme_dark, Icons.Filled.DarkMode),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            options.forEachIndexed { position, (mode, label, icon) ->
                ToggleButton(
                    checked = selected == mode,
                    onCheckedChange = { onSelect(mode) },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { role = Role.RadioButton },
                    shapes = when (position) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(label), maxLines = 1)
                }
            }
        }
    }
}

/**
 * A setting with a few exclusive options, drawn like [ThemePicker]: an Expressive connected
 * button group inside a list-shaped card. [options] are labels with an optional icon.
 */
@Composable
private fun ChoiceItem(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    options: List<Pair<String, ImageVector?>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val shape = ListItemDefaults.segmentedShapes(index, count).shape
    val colors = ListItemDefaults.segmentedColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.containerColor)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = colors.leadingContentColor)
            Spacer(Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.contentColor,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            options.forEachIndexed { position, (label, optionIcon) ->
                ToggleButton(
                    checked = selected == position,
                    onCheckedChange = { onSelect(position) },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { role = Role.RadioButton },
                    shapes = when (position) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                ) {
                    if (optionIcon != null) {
                        Icon(optionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(label, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- previews

@Preview(showBackground = true, backgroundColor = 0xFF0E0E0F)
@Composable
private fun SettingsPreview() {
    F1LivePreviewTheme {
        SettingsContent(
            state = SettingsUiState(
                themeMode = ThemeMode.DARK,
                dynamicColor = true,
                autoLiveUpdate = true,
                metricStyle = true,
                metricStyleSupported = false,
                version = "1.0",
            ),
            onBack = {},
            onThemeMode = {},
            onDynamicColor = {},
            onAutoLiveUpdate = {},
            onMetricStyle = {},
            onTestLiveUpdate = {},
            onCheckNow = {},
        )
    }
}
