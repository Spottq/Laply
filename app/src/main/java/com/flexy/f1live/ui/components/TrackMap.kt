package com.flexy.f1live.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.flexy.f1live.R
import com.flexy.f1live.data.CircuitMaps
import com.flexy.f1live.model.RaceWeekend

/**
 * What a screen shows for a circuit: F1's official detailed map (corner numbers, sectors) with the
 * number of turns; F1's clean outline when there is no detailed map; and - only for the few circuits
 * F1 publishes neither for - a picture the resolver found elsewhere. The old 2018 "Circuit maps
 * 16x9" CDN set is deliberately never used: its opaque labels turn into black squares here.
 */
@Immutable
data class CircuitOutline(
    val outlineUrl: String?,
    val turns: Int?,
    val fallbackMapUrl: String? = null,
    val detailedMapUrl: String? = null,
) {
    val isEmpty: Boolean get() = detailedMapUrl == null && outlineUrl == null && fallbackMapUrl == null

    companion object {
        /** True when [of] would take a fallback picture, i.e. it is worth resolving one. */
        fun needsFallback(weekend: RaceWeekend): Boolean =
            CircuitMaps.f1OutlineUrl(weekend) == null && CircuitMaps.f1MapUrl(weekend) == null

        /**
         * [resolvedMapUrl] is what [com.flexy.f1live.data.CircuitMapResolver] returned; it is only
         * kept when it cannot be the detailed CDN map (see [needsFallback]).
         */
        fun of(weekend: RaceWeekend, resolvedMapUrl: String? = null): CircuitOutline =
            CircuitOutline(
                outlineUrl = CircuitMaps.f1OutlineUrl(weekend),
                turns = CircuitMaps.turnsOf(weekend),
                fallbackMapUrl = resolvedMapUrl.takeIf { needsFallback(weekend) },
                detailedMapUrl = CircuitMaps.f1DetailedMapUrl(weekend),
            )
    }
}

/**
 * The circuit card: F1's detailed map in its own colours, with the number of turns under it. When
 * there is no detailed map (or it fails to load) the clean outline tinted with the theme - the Next
 * Round card's drawing - takes its place; a circuit with neither gets [TrackMapCard], and a circuit
 * with nothing at all draws nothing.
 */
@Composable
fun CircuitOutlineCard(
    circuit: CircuitOutline,
    modifier: Modifier = Modifier,
    maxMapWidth: Dp = 560.dp,
    maxOutlineWidth: Dp = 260.dp,
) {
    var detailedFailed by remember(circuit.detailedMapUrl) { mutableStateOf(false) }
    val detailed = circuit.detailedMapUrl.takeUnless { detailedFailed }
    if (detailed == null && circuit.outlineUrl == null) {
        TrackMapCard(url = circuit.fallbackMapUrl, modifier = modifier, corner = 28.dp)
        return
    }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (detailed != null) {
            AsyncImage(
                model = detailed,
                contentDescription = null,
                // Transparent PNG with a white track edge: drawn as published, never tinted.
                contentScale = ContentScale.Fit,
                onError = { detailedFailed = true },
                modifier = Modifier
                    .widthIn(max = maxMapWidth)
                    .fillMaxWidth()
                    // The detailed maps are 900 x 506.
                    .aspectRatio(900f / 506f),
            )
        } else {
            AsyncImage(
                model = circuit.outlineUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                // One line on transparency: SrcIn repaints it in the theme colour, light or dark.
                colorFilter = ColorFilter.tint(colors.onSurface, BlendMode.SrcIn),
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .widthIn(max = maxOutlineWidth)
                    .fillMaxWidth()
                    // F1 draws every outline on a 121 x 85 canvas.
                    .aspectRatio(121f / 85f),
            )
        }
        circuit.turns?.let { turns ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = pluralStringResource(R.plurals.circuit_turns, turns, turns),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * A circuit picture that is not one of F1's outlines (a Wikipedia page image), on its own plate.
 * Resolving it is the job of [com.flexy.f1live.data.CircuitMapResolver]; this only draws it.
 */
@Composable
fun TrackMapCard(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 24.dp,
    inset: Dp = 12.dp,
) {
    if (url == null) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(inset),
        )
    }
}
