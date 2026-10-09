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

    private val openLiveRequests = mutableIntStateOf(0)

    private val openStandingsRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openStandingsFrom(intent)
        setContent {
            val themeMode by AppSettings.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by AppSettings.dynamicColor.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
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

    private fun openStandingsFrom(intent: Intent?) {
        val tab = when (intent?.action) {
            ACTION_OPEN_DRIVER_STANDINGS -> StandingsTab.Drivers
            ACTION_OPEN_TEAM_STANDINGS -> StandingsTab.Constructors
            else -> return
        }
        StandingsTabRequests.request(tab)
        openStandingsRequests.intValue++
    }

    override fun onResume() {
        super.onResume()
        AutoFollow.onForeground(this)
    }

    companion object {
        const val ACTION_OPEN_LIVE = "com.flexy.f1live.action.OPEN_LIVE"

        const val ACTION_OPEN_DRIVER_STANDINGS = "com.flexy.f1live.action.OPEN_DRIVER_STANDINGS"
        const val ACTION_OPEN_TEAM_STANDINGS = "com.flexy.f1live.action.OPEN_TEAM_STANDINGS"
    }
}
