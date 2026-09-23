package com.flexy.f1live.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.flexy.f1live.live.FollowPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App colour mode. [DARK] is the default: the app was designed dark-first. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The user's app settings, one [StateFlow] per setting so Compose, the theme and the Live Update
 * service all observe the same value and react the moment it changes.
 *
 * SharedPreferences for the same reasons as [FollowPreferences]: a handful of scalars, read
 * synchronously in F1App.onCreate so the very first frame already has the right theme, and no
 * DataStore dependency for it.
 *
 * The "automatic Live Update" switch is deliberately *not* stored here: it is the Follow opt-in,
 * and [FollowPreferences] stays its single source of truth - [autoLiveUpdate] only re-exposes it,
 * so the Settings switch and the Follow button on the Live tab can never disagree.
 */
object AppSettings {

    private const val FILE = "app_settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_METRIC_STYLE = "metric_style"
    private const val KEY_WIDGET_TRACK_BACKGROUND = "widget_track_background"
    private const val KEY_WIDGET_SAMSUNG_BLUR = "widget_samsung_blur"
    private const val KEY_WIDGET_BLUR_ALPHA = "widget_blur_alpha"
    private const val KEY_WIDGET_TONE = "widget_tone"
    private const val KEY_CHECK_UPDATES = "check_updates"

    /**
     * Background opacities offered for the One UI blur style, as alpha 0..255: glass, light,
     * medium, solid. The launcher only blurs behind an alpha of 1..254, so none is 0 or 255.
     * Values from twidget's OPACITY_PRESETS (MIT, (c) 2026 Josh Skinner).
     */
    val WIDGET_BLUR_ALPHAS: List<Int> = listOf(38, 102, 178, 240)
    private const val DEFAULT_WIDGET_BLUR_ALPHA = 178

    /** First API level with `Notification.MetricStyle` (Android 17, "Cinnamon Bun"). */
    const val API_METRIC_STYLE = Build.VERSION_CODES.CINNAMON_BUN

    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(true)

    /** Monet colours from the wallpaper; off means the app's own F1 red scheme. */
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _metricStyle = MutableStateFlow(true)

    /**
     * Whether the Live Update may use Android 17's MetricStyle. Only the preference: callers also
     * check [metricStyleSupported], so turning this on on an older phone changes nothing.
     */
    val metricStyle: StateFlow<Boolean> = _metricStyle.asStateFlow()

    private val _widgetTrackBackground = MutableStateFlow(true)

    /**
     * Whether the home-screen widgets draw the circuit outline as a faint background decoration.
     * WidgetUpdater observes it and redraws every placed widget when it changes.
     */
    val widgetTrackBackground: StateFlow<Boolean> = _widgetTrackBackground.asStateFlow()

    private val _widgetSamsungBlur = MutableStateFlow(true)

    /**
     * Samsung One UI Home (7+): draw the widgets as translucent glass the launcher blurs the
     * wallpaper behind, like Samsung's own widgets. Ignored on other launchers and phones.
     */
    val widgetSamsungBlur: StateFlow<Boolean> = _widgetSamsungBlur.asStateFlow()

    private val _widgetBlurAlpha = MutableStateFlow(DEFAULT_WIDGET_BLUR_ALPHA)

    /** Opacity of that glass, one of [WIDGET_BLUR_ALPHAS]. */
    val widgetBlurAlpha: StateFlow<Int> = _widgetBlurAlpha.asStateFlow()

    private val _widgetTone = MutableStateFlow(ThemeMode.SYSTEM)

    /** Light or dark glass (and the text on it), or following the system. */
    val widgetTone: StateFlow<ThemeMode> = _widgetTone.asStateFlow()

    private val _checkUpdates = MutableStateFlow(true)

    /**
     * Whether a daily background check looks for a new release on GitHub and notifies about it.
     * F1App observes it and schedules or cancels UpdateCheckWorker.
     */
    val checkUpdates: StateFlow<Boolean> = _checkUpdates.asStateFlow()

    /** Same flow as the Follow button's, see the class comment. */
    val autoLiveUpdate: StateFlow<Boolean> get() = FollowPreferences.enabled

    val metricStyleSupported: Boolean
        get() = Build.VERSION.SDK_INT >= API_METRIC_STYLE

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Called from F1App.onCreate, before any activity draws. */
    fun load(context: Context) {
        val prefs = prefs(context)
        _themeMode.value = prefs.getString(KEY_THEME, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.DARK
        _dynamicColor.value = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        _metricStyle.value = prefs.getBoolean(KEY_METRIC_STYLE, true)
        _widgetTrackBackground.value = prefs.getBoolean(KEY_WIDGET_TRACK_BACKGROUND, true)
        _widgetSamsungBlur.value = prefs.getBoolean(KEY_WIDGET_SAMSUNG_BLUR, true)
        _widgetBlurAlpha.value = prefs.getInt(KEY_WIDGET_BLUR_ALPHA, DEFAULT_WIDGET_BLUR_ALPHA)
            .takeIf { it in WIDGET_BLUR_ALPHAS } ?: DEFAULT_WIDGET_BLUR_ALPHA
        _widgetTone.value = prefs.getString(KEY_WIDGET_TONE, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM
        _checkUpdates.value = prefs.getBoolean(KEY_CHECK_UPDATES, true)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        _dynamicColor.value = enabled
    }

    fun setMetricStyle(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_METRIC_STYLE, enabled).apply()
        _metricStyle.value = enabled
    }

    fun setWidgetTrackBackground(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIDGET_TRACK_BACKGROUND, enabled).apply()
        _widgetTrackBackground.value = enabled
    }

    fun setWidgetSamsungBlur(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIDGET_SAMSUNG_BLUR, enabled).apply()
        _widgetSamsungBlur.value = enabled
    }

    fun setWidgetBlurAlpha(context: Context, alpha: Int) {
        if (alpha !in WIDGET_BLUR_ALPHAS) return
        prefs(context).edit().putInt(KEY_WIDGET_BLUR_ALPHA, alpha).apply()
        _widgetBlurAlpha.value = alpha
    }

    fun setWidgetTone(context: Context, tone: ThemeMode) {
        prefs(context).edit().putString(KEY_WIDGET_TONE, tone.name).apply()
        _widgetTone.value = tone
    }

    fun setCheckUpdates(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHECK_UPDATES, enabled).apply()
        _checkUpdates.value = enabled
    }
}
