package com.flexy.f1live.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.flexy.f1live.live.FollowPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

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

    // Values from twidget's OPACITY_PRESETS (MIT, (c) 2026 Josh Skinner).
    val WIDGET_BLUR_ALPHAS: List<Int> = listOf(38, 102, 178, 240)
    private const val DEFAULT_WIDGET_BLUR_ALPHA = 178

    const val API_METRIC_STYLE = Build.VERSION_CODES.CINNAMON_BUN

    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(true)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _metricStyle = MutableStateFlow(true)
    val metricStyle: StateFlow<Boolean> = _metricStyle.asStateFlow()

    private val _widgetTrackBackground = MutableStateFlow(true)
    val widgetTrackBackground: StateFlow<Boolean> = _widgetTrackBackground.asStateFlow()

    private val _widgetSamsungBlur = MutableStateFlow(true)
    val widgetSamsungBlur: StateFlow<Boolean> = _widgetSamsungBlur.asStateFlow()

    private val _widgetBlurAlpha = MutableStateFlow(DEFAULT_WIDGET_BLUR_ALPHA)
    val widgetBlurAlpha: StateFlow<Int> = _widgetBlurAlpha.asStateFlow()

    private val _widgetTone = MutableStateFlow(ThemeMode.SYSTEM)
    val widgetTone: StateFlow<ThemeMode> = _widgetTone.asStateFlow()

    private val _checkUpdates = MutableStateFlow(true)
    val checkUpdates: StateFlow<Boolean> = _checkUpdates.asStateFlow()

    val autoLiveUpdate: StateFlow<Boolean> get() = FollowPreferences.enabled

    val metricStyleSupported: Boolean
        get() = Build.VERSION.SDK_INT >= API_METRIC_STYLE

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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
