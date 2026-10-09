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

/**
 * The classification card: header, one row per driver, rounded foot.
 *
 * Shared by the Live screen and the past-session results screen - the two show the same table, one
 * fed by a socket and one by a finished result set, so the rows live here rather than in either.
 */

val ClassificationCorner = 28.dp
val ClassificationHorizontal = 16.dp

val OverallFastestColor = Color(0xFFB14BF4)
val PersonalFastestColor = Color(0xFF3FB559)

/** Default column labels of the expandable detail row. */
val SectorLabels = listOf("S1", "S2", "S3")

/** Qualifying results carry Q1/Q2/Q3 in the same three slots. */
val QualifyingLabels = listOf("Q1", "Q2", "Q3")

@Composable
fun ClassificationCardTop(
    title: String,
    isRace: Boolean,
    /** Space outside the card; a large-screen pane supplies its own margins and passes 0. */
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
    /** Purple "FL" badge for the driver credited with the fastest lap of a finished race. */
    fastestLap: Boolean = false,
    sectorLabels: List<String> = SectorLabels,
    /**
     * Show the driver's best sectors of the session instead of the last lap's. The feed's live
     * sectors are whatever the car did on its most recent lap - meaningless once the flag is out.
     */
    useBestSectors: Boolean = false,
    /** Space outside the card; a large-screen pane supplies its own margins and passes 0. */
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
                // Stops only mean something in a race: in practice and qualifying every run
                // ends in the pits, and the tyre chain already shows the runs.
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

/**
 * The tyre history under the sectors: one chip per stint with the laps it lasted, oldest first,
 * and the pit stops beside it. Nothing for a source that publishes neither.
 */
@Composable
fun TyreRow(driver: DriverTiming, showPitStops: Boolean) {
    val stops = if (showPitStops) driver.pitStops else 0
    if (driver.stints.isEmpty() && stops == 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 38.dp, top = 2.dp, bottom = 4.dp),
    ) {
        // Two sector columns wide; with SectorRow's two 16 dp gaps after it, the stops sit
        // exactly under S3.
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
                // The laps come from the stints; when they do not add up to the count (a
                // red-flag tyre change, a stop the stints have not caught up with) the count
                // stands alone.
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
            // Separator, chip and laps stay together when a long practice chain wraps.
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
                        // The last stint is the set on the car now.
                        fontWeight = if (index == stints.lastIndex) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * The compound's initial on its colour: a filled disc for a new set, a faded one with a dashed rim
 * for a set that had already been run.
 */
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

/** "2 · L15, L38": the count, then the lap of each stop when it is known. */
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
