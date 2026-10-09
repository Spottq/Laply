package com.flexy.f1live.widget

import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.glance.unit.ColorProvider
import com.flexy.f1live.R
import com.flexy.f1live.settings.ThemeMode
import com.flexy.f1live.ui.theme.F1DarkScheme
import com.flexy.f1live.ui.theme.F1LightScheme
import androidx.glance.color.ColorProvider as DayNightColorProvider

sealed interface WidgetColor {
    val provider: ColorProvider
    fun applyTextColor(views: RemoteViews, viewId: Int)

    class Res(@param:ColorRes private val res: Int) : WidgetColor {
        override val provider: ColorProvider = ColorProvider(res)
        override fun applyTextColor(views: RemoteViews, viewId: Int) =
            views.setColor(viewId, "setTextColor", res)
    }

    class DayNight(private val day: Color, private val night: Color) : WidgetColor {
        override val provider: ColorProvider = DayNightColorProvider(day = day, night = night)
        override fun applyTextColor(views: RemoteViews, viewId: Int) =
            views.setColorInt(viewId, "setTextColor", day.toArgb(), night.toArgb())
    }
}

class WidgetColors(
    val background: WidgetColor,
    val inset: WidgetColor,
    val primary: WidgetColor,
    val onSurface: WidgetColor,
    val onSurfaceVariant: WidgetColor,
    val primaryContainer: WidgetColor,
    val onPrimaryContainer: WidgetColor,
    val live: WidgetColor,
    val onLive: WidgetColor,
    val decoration: WidgetColor = primary,
    val decorationAlpha: Float = 0.12f,
    val pill: WidgetColor = primaryContainer,
    val onPill: WidgetColor = onPrimaryContainer,
) {
    companion object {
        private val liveRed = WidgetColor.DayNight(Color(0xFFC00500), Color(0xFFE10600))
        private val onLiveRed = WidgetColor.DayNight(Color.White, Color.White)

        val Dynamic = WidgetColors(
            background = WidgetColor.Res(R.color.widget_background),
            inset = WidgetColor.Res(R.color.widget_inset),
            primary = WidgetColor.Res(R.color.widget_primary),
            onSurface = WidgetColor.Res(R.color.widget_on_surface),
            onSurfaceVariant = WidgetColor.Res(R.color.widget_on_surface_variant),
            primaryContainer = WidgetColor.Res(R.color.widget_primary_container),
            onPrimaryContainer = WidgetColor.Res(R.color.widget_on_primary_container),
            live = liveRed,
            onLive = onLiveRed,
        )

        val Brand = WidgetColors(
            background = dayNight { it.surfaceContainer },
            inset = dayNight { it.surfaceContainerHighest },
            primary = dayNight { it.primary },
            onSurface = dayNight { it.onSurface },
            onSurfaceVariant = dayNight { it.onSurfaceVariant },
            primaryContainer = dayNight { it.primaryContainer },
            onPrimaryContainer = dayNight { it.onPrimaryContainer },
            live = liveRed,
            onLive = onLiveRed,
        )

        fun of(dynamicColor: Boolean): WidgetColors = if (dynamicColor) Dynamic else Brand

        fun glass(tone: ThemeMode, dynamicColor: Boolean): WidgetColors {
            fun pick(light: Color, dark: Color): WidgetColor = when (tone) {
                ThemeMode.LIGHT -> WidgetColor.DayNight(light, light)
                ThemeMode.DARK -> WidgetColor.DayNight(dark, dark)
                ThemeMode.SYSTEM -> WidgetColor.DayNight(light, dark)
            }
            fun accent(@ColorRes lightRes: Int, @ColorRes darkRes: Int, @ColorRes systemRes: Int, light: Color, dark: Color) =
                if (dynamicColor) {
                    when (tone) {
                        ThemeMode.LIGHT -> WidgetColor.Res(lightRes)
                        ThemeMode.DARK -> WidgetColor.Res(darkRes)
                        ThemeMode.SYSTEM -> WidgetColor.Res(systemRes)
                    }
                } else {
                    pick(light, dark)
                }
            return WidgetColors(
                background = pick(Color.White, Color(0xFF101010)),
                inset = pick(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.12f)),
                primary = accent(
                    android.R.color.system_accent1_600, android.R.color.system_accent1_200, R.color.widget_primary,
                    F1LightScheme.primary, F1DarkScheme.primary,
                ),
                onSurface = pick(Color(0xFF15161A), Color.White),
                onSurfaceVariant = pick(Color(0xFF15161A).copy(alpha = 0.7f), Color.White.copy(alpha = 0.72f)),
                primaryContainer = accent(
                    android.R.color.system_accent1_100, android.R.color.system_accent1_700, R.color.widget_primary_container,
                    F1LightScheme.primaryContainer, F1DarkScheme.primaryContainer,
                ),
                onPrimaryContainer = accent(
                    android.R.color.system_accent1_900, android.R.color.system_accent1_100, R.color.widget_on_primary_container,
                    F1LightScheme.onPrimaryContainer, F1DarkScheme.onPrimaryContainer,
                ),
                live = liveRed,
                onLive = onLiveRed,
                decoration = pick(Color(0xFF15161A), Color.White),
                decorationAlpha = 0.2f,
            )
        }

        private inline fun dayNight(role: (androidx.compose.material3.ColorScheme) -> Color) =
            WidgetColor.DayNight(day = role(F1LightScheme), night = role(F1DarkScheme))
    }
}
