package com.flexy.f1live.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.model.TrackFlag

// ---------------------------------------------------------------- colours

private val FallbackTeamColor = Color(0xFF8A8A8A)

fun teamColor(hex: String?): Color {
    val cleaned = hex?.trim()?.removePrefix("#") ?: return FallbackTeamColor
    if (cleaned.length != 6 && cleaned.length != 8) return FallbackTeamColor
    val value = cleaned.toLongOrNull(16) ?: return FallbackTeamColor
    return if (cleaned.length == 6) Color(value or 0xFF000000L) else Color(value)
}

fun tyreColor(compound: String?): Color? = when (compound?.uppercase()) {
    "SOFT" -> Color(0xFFE1483C)
    "MEDIUM" -> Color(0xFFF5D64A)
    "HARD" -> Color(0xFFEFEFEF)
    "INTERMEDIATE" -> Color(0xFF3FB559)
    "WET" -> Color(0xFF3E7FD6)
    else -> null
}

fun flagColor(flag: String?): Color = when (flag?.uppercase()) {
    "GREEN", "CLEAR" -> Color(0xFF3FB559)
    "YELLOW", "DOUBLE YELLOW" -> Color(0xFFF5D64A)
    "RED" -> Color(0xFFE1483C)
    "BLUE" -> Color(0xFF3E7FD6)
    "CHEQUERED" -> Color(0xFFEFEFEF)
    else -> Color(0xFF8A8A8A)
}

fun trackFlagColor(flag: TrackFlag): Color = when (flag) {
    TrackFlag.GREEN -> Color(0xFF3FB559)
    TrackFlag.YELLOW -> Color(0xFFF5D64A)
    TrackFlag.RED -> Color(0xFFE1483C)
    TrackFlag.SC, TrackFlag.VSC, TrackFlag.VSC_ENDING -> Color(0xFFF5A623)
    TrackFlag.UNKNOWN -> Color(0xFF8A8A8A)
}

fun statusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.STARTED -> "In Progress"
    SessionStatus.ABORTED -> "Red Flag"
    SessionStatus.FINISHED, SessionStatus.FINALISED, SessionStatus.ENDS -> "Finished"
    SessionStatus.INACTIVE -> "Not Started"
    SessionStatus.UNKNOWN -> ""
}

fun flagCdnUrl(countryCode: String?): String? =
    countryCode?.takeIf { it.isNotBlank() }?.let { "https://flagcdn.com/w80/" + it.lowercase() + ".png" }

val MonoFamily: FontFamily = FontFamily.Monospace

// ---------------------------------------------------------------- avatars

@Composable
fun DriverAvatar(
    driver: DriverTiming,
    size: Dp,
    modifier: Modifier = Modifier,
    ringWidth: Dp = 0.dp,
    flagBadge: Boolean = true,
    crossfade: Boolean = false,
    centered: Boolean = false,
) {
    val color = rememberTeamColor(driver.teamColorHex)
    val badge = (size * 0.42f).coerceAtLeast(12.dp)
    val overhang = badge / 3
    Box(
        modifier = if (centered) {
            modifier.size(width = size + overhang * 2, height = size + overhang)
        } else {
            modifier.size(size + overhang)
        },
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .align(if (centered) Alignment.TopCenter else Alignment.TopStart)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.30f))
                .then(if (ringWidth > 0.dp) Modifier.border(ringWidth, color, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val loaded = remember(driver.headshotUrl) { mutableStateOf(false) }
            Text(
                text = driver.tla,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.30f).sp,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = if (loaded.value) 0f else 1f },
            )
            if (!driver.headshotUrl.isNullOrBlank()) {
                AsyncImage(
                    model = rememberImageModel(driver.headshotUrl, crossfade),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { loaded.value = true },
                    onError = { loaded.value = false },
                    modifier = Modifier.size(size).clip(CircleShape),
                )
            }
        }
        val flagUrl = flagCdnUrl(driver.countryCode)
        if (flagBadge && flagUrl != null) {
            AsyncImage(
                model = rememberImageModel(flagUrl, crossfade),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badge)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
fun rememberTeamColor(hex: String?): Color = remember(hex) { teamColor(hex) }

@Composable
private fun rememberImageModel(url: String, crossfade: Boolean): Any {
    if (!crossfade) return url
    val context = LocalContext.current
    return remember(url, context) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(CrossfadeMillis)
            .build()
    }
}

private const val CrossfadeMillis = 150

@Composable
fun CountryFlag(countryCode: String?, modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    val url = flagCdnUrl(countryCode)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

// ---------------------------------------------------------------- chips and dots

@Composable
fun PitChip(modifier: Modifier = Modifier) {
    Text(
        text = "PIT",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun TyreDot(compound: String?, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val color = tyreColor(compound) ?: return
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape),
    )
}

@Composable
fun LabelledDot(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- checkered header

@Composable
fun CheckeredBackground(
    modifier: Modifier = Modifier,
    cell: Dp = 22.dp,
    squareAlpha: Float = 0.06f,
    fullScreen: Boolean = false,
) {
    val surface = MaterialTheme.colorScheme.surface
    val square = MaterialTheme.colorScheme.onSurface
    Spacer(
        modifier = modifier.drawWithCache {
            val step = cell.toPx()
            val squareColor = square.copy(alpha = squareAlpha)
            val fade = if (fullScreen) {
                Brush.verticalGradient(
                    0f to surface.copy(alpha = 0f),
                    0.35f to surface.copy(alpha = 0.30f),
                    1f to surface.copy(alpha = 0.70f),
                    endY = size.height,
                )
            } else {
                Brush.verticalGradient(
                    0f to surface.copy(alpha = 0f),
                    0.40f to surface.copy(alpha = 0.30f),
                    0.72f to surface.copy(alpha = 0.85f),
                    0.86f to surface,
                    1f to surface,
                    endY = size.height,
                )
            }
            val cellSize = Size(step, step)
            onDrawBehind {
                drawRect(surface)
                if (step <= 0f) return@onDrawBehind
                val cols = (size.width / step).toInt() + 1
                val rows = if (fullScreen) {
                    (size.height / step).toInt() + 1
                } else {
                    (size.height * 0.86f / step).toInt()
                }
                for (row in 0 until rows) {
                    var col = row % 2
                    while (col < cols) {
                        drawRect(
                            color = squareColor,
                            topLeft = Offset(col * step, row * step),
                            size = cellSize,
                        )
                        col += 2
                    }
                }
                drawRect(brush = fade)
            }
        },
    )
}
