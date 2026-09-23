package com.flexy.f1live.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.flexy.f1live.R
import com.flexy.f1live.data.CircuitMaps
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.ui.SampleData
import com.flexy.f1live.ui.components.CountryFlag
import com.flexy.f1live.ui.components.formatDateRange
import com.flexy.f1live.ui.components.formatCountdown
import com.flexy.f1live.ui.components.formatDayTime
import com.flexy.f1live.ui.theme.F1LivePreviewTheme
import kotlinx.coroutines.delay

private const val MINUTE_MILLIS = 60_000L

/**
 * The weekend coming up, shown on the Live tab once the displayed session is over: round, Grand
 * Prix, circuit and dates on top, and an inset strip with the next session and a countdown to it.
 *
 * The circuit is F1's plain outline (see [CircuitMaps.f1OutlineUrl]) - just the track, without the
 * DRS zones and turn numbers of the detailed map - tinted with the theme. Coil loads it lazily and
 * caches it; the card never waits for it, and a circuit without an outline simply has none.
 */
@Composable
fun NextRoundCard(
    next: UpcomingSession,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    nowMillis: Long = rememberMinuteClock(),
    /**
     * A narrow large-screen pane (~300dp): a smaller title and outline, so a long Grand Prix name
     * still fits on two lines next to the map.
     */
    compact: Boolean = false,
) {
    val weekend = next.weekend
    val session = next.session
    val colors = MaterialTheme.colorScheme
    val openLabel = stringResource(R.string.next_round_open_schedule)
    val mapUrl = remember(weekend.season, weekend.round) { CircuitMaps.f1OutlineUrl(weekend) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(colors.surfaceContainerHigh)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = openLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.next_round_overline, weekend.round).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CountryFlag(
                        countryCode = weekend.countryCode,
                        modifier = Modifier.size(width = 28.dp, height = 20.dp),
                        corner = 4.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = weekend.name,
                        style = if (compact) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOf(weekend.circuitName, weekend.locality)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = weekendDates(weekend),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                }
            }
            if (mapUrl != null) {
                Spacer(Modifier.width(12.dp))
                AsyncImage(
                    model = mapUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    // The outline is one dark line on transparency: SrcIn repaints it in the
                    // theme's colour and leaves the rest see-through, in light and dark alike.
                    colorFilter = ColorFilter.tint(colors.onSurfaceVariant, BlendMode.SrcIn),
                    modifier = Modifier
                        .width(if (compact) 84.dp else 112.dp)
                        // F1 draws every outline on a 121 x 85 canvas.
                        .aspectRatio(121f / 85f),
                )
            }
        }

        // The inset: what actually happens next, and how long until it does.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceContainerHighest)
                .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatDayTime(session.startUtcMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            session.startUtcMillis?.let { start ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(colors.primaryContainer)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = countdownText(start - nowMillis),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onPrimaryContainer,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** First to last known session of the weekend, e.g. "4 – 6 Sep". */
private fun weekendDates(weekend: RaceWeekend): String {
    val starts = weekend.sessions.mapNotNull { it.startUtcMillis }
    return formatDateRange(starts.minOrNull(), starts.maxOrNull() ?: weekend.raceStartUtcMillis)
}

/** Wall-clock time that ticks on every minute boundary - enough for a d/h/m countdown. */
@Composable
private fun rememberMinuteClock(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            val current = System.currentTimeMillis()
            value = current
            delay(MINUTE_MILLIS - current % MINUTE_MILLIS)
        }
    }
    return now
}

/** "in 2d 14h", "in 3h 12m", "in 8 min"; "Starting now" - the wording the widgets share. */
@Composable
private fun countdownText(remainingMillis: Long): String =
    formatCountdown(LocalContext.current.resources, remainingMillis)

// ---------------------------------------------------------------- previews

@Preview(name = "Next round card", showBackground = true, backgroundColor = 0xFF0E0E0F, widthDp = 400)
@Composable
private fun NextRoundCardPreview() {
    val weekend = SampleData.weekend
    val session = weekend.sessions.first()
    F1LivePreviewTheme {
        NextRoundCard(
            next = UpcomingSession(weekend, session),
            onClick = {},
            // A fixed "now" 2 days 14 hours before the session, so the countdown is stable.
            nowMillis = (session.startUtcMillis ?: 0L) - (2L * 24 + 14) * 60 * MINUTE_MILLIS,
            modifier = Modifier.padding(16.dp),
        )
    }
}
