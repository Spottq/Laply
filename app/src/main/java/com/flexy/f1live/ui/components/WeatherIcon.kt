package com.flexy.f1live.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.flexy.f1live.R
import kotlin.math.roundToInt

private const val ICON_BASE = "https://www.gstatic.com/weather/conditions/v2/svg/"

/**
 * Google Weather's condition icon for a WMO weather code (as Open-Meteo reports it) - the scalloped
 * sun and outlined clouds of Google's weather pages - in its light or dark drawing to match the
 * theme. Coil fetches the SVG once and keeps it in its disk cache.
 */
@Composable
fun WeatherIcon(
    code: Int?,
    modifier: Modifier = Modifier,
    isDay: Boolean = true,
    size: Dp = 24.dp,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    AsyncImage(
        model = weatherIconUrl(code, isDay, dark),
        contentDescription = stringResource(weatherLabel(code)),
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
    )
}

fun weatherIconUrl(code: Int?, isDay: Boolean, dark: Boolean): String =
    ICON_BASE + weatherIconName(code, isDay) + if (dark) "_dark.svg" else "_light.svg"

/** WMO weather interpretation code -> the name of Google's condition icon. */
internal fun weatherIconName(code: Int?, isDay: Boolean): String = when (code) {
    0 -> if (isDay) "sunny" else "clear_night"
    1 -> if (isDay) "mostly_sunny" else "mostly_clear_night"
    2 -> if (isDay) "partly_cloudy" else "partly_cloudy_night"
    45, 48 -> "haze_fog"
    in 51..57 -> "drizzle"
    61, 63, 80, 81 -> "rain_showers"
    65, 82 -> "heavy_rain"
    66, 67 -> "wintry_mix"
    71, 85 -> "flurries"
    73, 77 -> "snow_showers"
    75, 86 -> "heavy_snow"
    95 -> "thunderstorms"
    96, 99 -> "strong_thunderstorms"
    else -> "cloudy"
}

/** What the icon shows, for screen readers. */
@StringRes
fun weatherLabel(code: Int?): Int = when (code) {
    0, 1 -> R.string.weather_clear
    2 -> R.string.weather_partly_cloudy
    45, 48 -> R.string.weather_fog
    in 51..57 -> R.string.weather_drizzle
    in 61..67, in 80..82 -> R.string.weather_rain
    in 71..77, 85, 86 -> R.string.weather_snow
    in 95..99 -> R.string.weather_thunderstorm
    else -> R.string.weather_overcast
}

/** "27°", or a dash when the forecast has no temperature. */
fun formatDegrees(celsius: Double?): String = celsius?.let { it.roundToInt().toString() + "°" } ?: "–"

private const val RAIN_LIKELY_PCT = 40
private val RainBlue = Color(0xFF3D8BFD)

/** A drop and a percentage; blue once rain is a real prospect. */
@Composable
fun RainChance(pct: Int, modifier: Modifier = Modifier, quietColor: Color = LocalContentColor.current) {
    val tint = if (pct >= RAIN_LIKELY_PCT) RainBlue else quietColor
    val description = stringResource(R.string.rain_chance, pct)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.WaterDrop,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = "$pct%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
    }
}
