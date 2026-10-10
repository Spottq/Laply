package com.flexy.f1live

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flexy.f1live.live.AutoFollow
import com.flexy.f1live.settings.AppSettings
import com.flexy.f1live.settings.ThemeMode
import com.flexy.f1live.ui.App
import com.flexy.f1live.ui.standings.StandingsTab
import com.flexy.f1live.ui.standings.StandingsTabRequests
import com.flexy.f1live.ui.theme.F1LiveTheme

class MainActivity : ComponentActivity() {

    /**
     * Bumped by every home-screen widget tap that reaches an already running activity, so [App]
     * switches to the Live tab even when the user left the app on another one.
     */
    private val openLiveRequests = mutableIntStateOf(0)

    /** Bumped by every standings widget tap, so [App] opens the Standings tab. */
    private val openStandingsRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A standings widget tap that started the app. Not again after a configuration change:
        // by then the user may have moved on to another tab.
        if (savedInstanceState == null) openStandingsFrom(intent)
        setContent {
            val themeMode by AppSettings.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by AppSettings.dynamicColor.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // The plain enableEdgeToEdge() above picks bar icon colours from the *system* night
            // mode; with an in-app theme choice they must follow the app instead, or a light app
            // on a dark phone gets white icons on a white status bar.
            DisposableEffect(darkTheme) {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            F1LiveTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                App(
                    openLiveRequest = openLiveRequests.intValue,
                    openStandingsRequest = openStandingsRequests.intValue,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_LIVE) openLiveRequests.intValue++
        openStandingsFrom(intent)
    }

    /** The standings widgets open their own table: the drivers' or the constructors'. */
    private fun openStandingsFrom(intent: Intent?) {
        val tab = when (intent?.action) {
            ACTION_OPEN_DRIVER_STANDINGS -> StandingsTab.Drivers
            ACTION_OPEN_TEAM_STANDINGS -> StandingsTab.Constructors
            else -> return
        }
        StandingsTabRequests.request(tab)
        openStandingsRequests.intValue++
    }

    /**
     * With Follow on and a session running per the calendar, bring the Live Update up now. Done
     * here rather than in F1App because only a foreground app may start a foreground service at
     * will - this also rescues a session alarm whose background start Android refused.
     */
    override fun onResume() {
        super.onResume()
        AutoFollow.onForeground(this)
    }

    companion object {
        /** The home-screen widgets' tap: open the app on the Live tab. */
        const val ACTION_OPEN_LIVE = "com.flexy.f1live.action.OPEN_LIVE"

        /** The standings widgets' taps: open the Standings tab on the drivers' or teams' table. */
        const val ACTION_OPEN_DRIVER_STANDINGS = "com.flexy.f1live.action.OPEN_DRIVER_STANDINGS"
        const val ACTION_OPEN_TEAM_STANDINGS = "com.flexy.f1live.action.OPEN_TEAM_STANDINGS"
    }
}
