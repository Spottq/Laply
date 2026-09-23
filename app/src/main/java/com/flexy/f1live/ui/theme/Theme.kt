package com.flexy.f1live.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** F1 brand red. */
val F1Red = Color(0xFFE10600)
private val F1RedDim = Color(0xFF8E0400)

internal val F1DarkScheme = darkColorScheme(
    primary = F1Red,
    onPrimary = Color.White,
    primaryContainer = F1RedDim,
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFB9C4D0),
    onSecondary = Color(0xFF17242F),
    secondaryContainer = Color(0xFF2C3945),
    onSecondaryContainer = Color(0xFFD6E1EC),
    tertiary = Color(0xFFF6C445),
    onTertiary = Color(0xFF3A2E00),
    background = Color(0xFF0E0E0F),
    onBackground = Color(0xFFE6E1E1),
    surface = Color(0xFF0E0E0F),
    onSurface = Color(0xFFE6E1E1),
    surfaceVariant = Color(0xFF2B2A2A),
    onSurfaceVariant = Color(0xFFA9A3A3),
    surfaceContainerLowest = Color(0xFF090909),
    surfaceContainerLow = Color(0xFF161617),
    surfaceContainer = Color(0xFF1B1B1C),
    surfaceContainerHigh = Color(0xFF252526),
    surfaceContainerHighest = Color(0xFF303031),
    outline = Color(0xFF6E6767),
    outlineVariant = Color(0xFF3A3838),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * Light counterpart of [F1DarkScheme], picked in Settings. Same brand red; neutral, very slightly
 * warm surfaces so the timing tower's team colours stay the loudest thing on screen, as in dark.
 */
internal val F1LightScheme = lightColorScheme(
    primary = Color(0xFFC00500),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410000),
    secondary = Color(0xFF52606D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E1EC),
    onSecondaryContainer = Color(0xFF0F1D28),
    tertiary = Color(0xFF745B00),
    onTertiary = Color.White,
    background = Color(0xFFFBF8F8),
    onBackground = Color(0xFF1C1B1B),
    surface = Color(0xFFFBF8F8),
    onSurface = Color(0xFF1C1B1B),
    surfaceVariant = Color(0xFFE9E1E0),
    onSurfaceVariant = Color(0xFF5B5454),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F1F1),
    surfaceContainer = Color(0xFFEFEBEB),
    surfaceContainerHigh = Color(0xFFE9E5E5),
    surfaceContainerHighest = Color(0xFFE3DFDF),
    outline = Color(0xFF8D8484),
    outlineVariant = Color(0xFFD0C8C7),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

/**
 * [darkTheme] and [dynamicColor] come from Settings (see MainActivity); the defaults keep the
 * app's original dark F1 look for previews and any caller that does not care.
 */
@Composable
fun F1LiveTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // minSdk is 31, so Monet is always there; the check only documents where it comes from.
    val dynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        dynamic && darkTheme -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        darkTheme -> F1DarkScheme
        else -> F1LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Theme used by @Preview so previews never depend on a device wallpaper. */
@Composable
fun F1LivePreviewTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = F1DarkScheme, content = content)
}
