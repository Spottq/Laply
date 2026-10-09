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

@Immutable
data class CircuitOutline(
    val outlineUrl: String?,
    val turns: Int?,
    val fallbackMapUrl: String? = null,
    val detailedMapUrl: String? = null,
) {
    val isEmpty: Boolean get() = detailedMapUrl == null && outlineUrl == null && fallbackMapUrl == null

    companion object {
        fun needsFallback(weekend: RaceWeekend): Boolean =
            CircuitMaps.f1OutlineUrl(weekend) == null && CircuitMaps.f1MapUrl(weekend) == null

        fun of(weekend: RaceWeekend, resolvedMapUrl: String? = null): CircuitOutline =
            CircuitOutline(
                outlineUrl = CircuitMaps.f1OutlineUrl(weekend),
                turns = CircuitMaps.turnsOf(weekend),
                fallbackMapUrl = resolvedMapUrl.takeIf { needsFallback(weekend) },
                detailedMapUrl = CircuitMaps.f1DetailedMapUrl(weekend),
            )
    }
}

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
                contentScale = ContentScale.Fit,
                onError = { detailedFailed = true },
                modifier = Modifier
                    .widthIn(max = maxMapWidth)
                    .fillMaxWidth()
                    .aspectRatio(900f / 506f),
            )
        } else {
            AsyncImage(
                model = circuit.outlineUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(colors.onSurface, BlendMode.SrcIn),
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .widthIn(max = maxOutlineWidth)
                    .fillMaxWidth()
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
