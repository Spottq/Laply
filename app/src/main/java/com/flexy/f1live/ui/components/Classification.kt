package com.flexy.f1live.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flexy.f1live.R
import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.SectorTiming
import com.flexy.f1live.model.TyreStint

val ClassificationCorner = 28.dp
val ClassificationHorizontal = 16.dp

val OverallFastestColor = Color(0xFFB14BF4)
val PersonalFastestColor = Color(0xFF3FB559)

val SectorLabels = listOf("S1", "S2", "S3")

val QualifyingLabels = listOf("Q1", "Q2", "Q3")

@Composable
fun ClassificationCardTop(
    title: String,
    isRace: Boolean,
    horizontalPadding: Dp = ClassificationHorizontal,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = horizontalPadding)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = ClassificationCorner, topEnd = ClassificationCorner))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp)
            .padding(top = 18.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title.ifBlank { "Classification" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.driver),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(if (isRace) R.string.gap else R.string.time),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ClassificationCardBottom(horizontalPadding: Dp = ClassificationHorizontal) {
    Spacer(
        Modifier
            .padding(horizontal = horizontalPadding)
            .fillMaxWidth()
            .height(12.dp)
            .clip(
                RoundedCornerShape(
                    bottomStart = ClassificationCorner,
                    bottomEnd = ClassificationCorner,
                ),
            )
            .background(MaterialTheme.colorScheme.surfaceContainer),
    )
}

fun primaryTimeOf(driver: DriverTiming, isRace: Boolean): String =
    if (isRace) {
        if (driver.position == 1) "Leader" else driver.gapToLeader
    } else {
        driver.bestLapTime
    }

@Composable
fun DriverRow(
    driver: DriverTiming,
    isRace: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showPit: Boolean = true,
    fastestLap: Boolean = false,
    sectorLabels: List<String> = SectorLabels,
    useBestSectors: Boolean = false,
    horizontalPadding: Dp = ClassificationHorizontal,
) {
    val dimmed = driver.knockedOut || driver.retired || driver.stopped
    val teamColor = rememberTeamColor(driver.teamColorHex)
    Column(
        modifier = modifier
            .padding(horizontal = horizontalPadding)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .alpha(if (dimmed) 0.5f else 1f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (driver.position > 0) driver.position.toString() else "-",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(30.dp),
            )
            Spacer(Modifier.width(8.dp))
            DriverAvatar(driver = driver, size = 36.dp, ringWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = driver.shortName.ifBlank { driver.tla },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    TyreDot(driver.tyreCompound)
                    if (fastestLap) {
                        Spacer(Modifier.width(6.dp))
                        FastestLapChip()
                    }
                    if (showPit && driver.inPit) {
                        Spacer(Modifier.width(6.dp))
                        PitChip()
                    }
                }
                Text(
                    text = driver.teamName,
                    style = MaterialTheme.typography.labelSmall,
                    color = teamColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = primaryTimeOf(driver, isRace).ifBlank { "--" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                val secondary = if (isRace) {
                    driver.interval.ifBlank { if (driver.position == 1) driver.lastLapTime else "" }
                } else {
                    ""
                }
                if (secondary.isNotBlank()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val best = useBestSectors && driver.bestSectors.isNotEmpty()
            Column {
                SectorRow(
                    sectors = if (best) driver.bestSectors else driver.sectors,
                    labels = if (best) sectorLabels.map { "Best " + it } else sectorLabels,
                )
                TyreRow(driver = driver, showPitStops = isRace)
            }
        }
    }
}

@Composable
fun SectorRow(sectors: List<SectorTiming>, labels: List<String> = SectorLabels) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 38.dp, top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        sectors.take(3).forEachIndexed { index, sector ->
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = labels.getOrNull(index) ?: ("S" + (index + 1)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = sector.value.ifBlank { "--" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        sector.overallFastest -> OverallFastestColor
                        sector.personalFastest -> PersonalFastestColor
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
        if (sectors.isEmpty()) {
            Text(
                text = "No sector times",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TyreRow(driver: DriverTiming, showPitStops: Boolean) {
    val stops = if (showPitStops) driver.pitStops else 0
    if (driver.stints.isEmpty() && stops == 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 38.dp, top = 2.dp, bottom = 4.dp),
    ) {
        Column(modifier = Modifier.weight(2f)) {
            if (driver.stints.isNotEmpty()) {
                DetailLabel("Tyres")
                TyreChain(driver.stints)
            }
        }
        if (stops > 0) {
            Spacer(Modifier.width(32.dp))
            Column(modifier = Modifier.weight(1f)) {
                DetailLabel("Pit stops")
                PitStopsValue(stops, driver.pitStopLaps.takeIf { it.size == stops })
            }
        }
    }
}

@Composable
private fun DetailLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TyreChain(stints: List<TyreStint>) {
    FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        stints.forEachIndexed { index, stint ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 5.dp),
                    )
                }
                TyreChip(stint)
                if (stint.laps > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stint.laps.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = MonoFamily,
                        fontWeight = if (index == stints.lastIndex) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TyreChip(stint: TyreStint) {
    val tyre = tyreColor(stint.compound)
    val solid = stint.isNew && tyre != null
    val rim = if (solid) Color.Black.copy(alpha = 0.35f) else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(16.dp)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val radius = (size.minDimension - stroke) / 2
                if (tyre != null) {
                    drawCircle(color = if (stint.isNew) tyre else tyre.copy(alpha = 0.35f), radius = radius)
                }
                drawCircle(
                    color = rim,
                    radius = radius,
                    style = Stroke(
                        width = stroke,
                        pathEffect = if (stint.isNew) {
                            null
                        } else {
                            PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.5.dp.toPx()))
                        },
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stint.compound?.take(1) ?: "?",
            fontSize = 9.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (solid) Color.Black else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PitStopsValue(stops: Int, laps: List<Int>?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium)) {
                append(stops.toString())
            }
            if (!laps.isNullOrEmpty()) {
                withStyle(SpanStyle(color = muted, fontSize = 11.sp)) {
                    append(laps.joinToString(separator = ", ", prefix = " · ") { "L$it" })
                }
            }
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun FastestLapChip(modifier: Modifier = Modifier) {
    Text(
        text = "FL",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(OverallFastestColor)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
