package com.flexy.f1live.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flexy.f1live.R
import com.flexy.f1live.settings.AppSettings
import com.flexy.f1live.settings.ThemeMode
import com.flexy.f1live.ui.settings.WidgetSettingsGroup
import com.flexy.f1live.ui.theme.F1LiveTheme
import com.flexy.f1live.widget.standings.StandingsWidgetUpdater

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_OK, resultIntent())

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
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        LargeFlexibleTopAppBar(
                            title = {
                                Text(stringResource(R.string.widget_config_title), fontWeight = FontWeight.Bold)
                            },
                            navigationIcon = {
                                IconButton(onClick = ::done) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_back))
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    },
                    floatingActionButton = {
                        ExtendedFloatingActionButton(
                            onClick = ::done,
                            icon = { Icon(Icons.Filled.Check, contentDescription = null) },
                            text = { Text(stringResource(R.string.widget_config_done)) },
                        )
                    },
                ) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding() + 96.dp,
                        ),
                    ) {
                        item(key = "widgets") { WidgetSettingsGroup() }
                    }
                }
            }
        }
    }

    private fun resultIntent(): Intent =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    private fun done() {
        WidgetUpdater.refresh(applicationContext)
        StandingsWidgetUpdater.refresh(applicationContext, fetch = false)
        setResult(RESULT_OK, resultIntent())
        finish()
    }
}
