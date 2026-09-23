package com.flexy.f1live.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flexy.f1live.R
import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.SectorTiming

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
            SectorRow(
                sectors = if (best) driver.bestSectors else driver.sectors,
                labels = if (best) sectorLabels.map { "Best " + it } else sectorLabels,
            )
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
