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

/**
 * One colour role of a widget. Both kinds follow the *system* light/dark mode on their own, because
 * the launcher resolves them when it inflates the RemoteViews, not when the app builds them:
 *  - [Res] points at `res/values(-night)(-v34)/widget_colors.xml`, which alias the system's
 *    Material You roles, so Monet colours also follow a wallpaper change without an app update;
 *  - [DayNight] carries the app's own F1 scheme, light and dark.
 *
 * [applyTextColor] exists for the one view Glance does not draw itself, the countdown Chronometer.
 */
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

/**
 * The handful of roles the widgets use - the same ones as the Live tab's next-round card: a
 * surface-container background, a highest-container inset, primary accents and a
 * primary-container countdown pill. Glance's own ColorProviders has no surface-container roles,
 * hence this small set of its own.
 */
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
    /** Tint of the faint background circuit ("Circuit background" setting) and its opacity. */
    val decoration: WidgetColor = primary,
    val decorationAlpha: Float = 0.12f,
    /** The countdown pill and its text. */
    val pill: WidgetColor = primaryContainer,
    val onPill: WidgetColor = onPrimaryContainer,
) {
    companion object {
        /** LIVE is red whatever the wallpaper says: it means the same thing everywhere in the app. */
        private val liveRed = WidgetColor.DayNight(Color(0xFFC00500), Color(0xFFE10600))
        private val onLiveRed = WidgetColor.DayNight(Color.White, Color.White)

        /** Material You, from the system's dynamic colour resources. */
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

        /** The app's own F1 red scheme (Settings > Dynamic colour off). */
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

        /**
         * Colours on One UI's blurred glass (see [OneUi]): the text follows the glass tone - dark
         * on light glass, white on dark - rather than the surface roles, which assume an opaque
         * background. [tone] SYSTEM switches with the system's dark theme like everything else.
         * [background] is unused here: the glass tint is set on the background view itself.
         */
        fun glass(tone: ThemeMode, dynamicColor: Boolean): WidgetColors {
            fun pick(light: Color, dark: Color): WidgetColor = when (tone) {
                ThemeMode.LIGHT -> WidgetColor.DayNight(light, light)
                ThemeMode.DARK -> WidgetColor.DayNight(dark, dark)
                ThemeMode.SYSTEM -> WidgetColor.DayNight(light, dark)
            }
            // Accents: the wallpaper palette's fixed tones, so a fixed tone does not follow the
            // system's night mode; the app-level roles already switch for SYSTEM.
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
                // On glass the circuit takes the text colour of the tone, a little stronger: the
                // blurred wallpaper behind would swallow a faint accent tint.
                decoration = pick(Color(0xFF15161A), Color.White),
                decorationAlpha = 0.2f,
            )
        }

        private inline fun dayNight(role: (androidx.compose.material3.ColorScheme) -> Color) =
            WidgetColor.DayNight(day = role(F1LightScheme), night = role(F1DarkScheme))
    }
}
