package com.flexy.f1live.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dissolves the content into the background at the top and bottom edges instead of cutting it off.
 *
 * Implemented as an alpha mask rather than a gradient of the background colour: the content is
 * rendered into an offscreen layer and a vertical gradient is composited over it with
 * [BlendMode.DstIn], which multiplies the destination alpha. That keeps it correct on any surface,
 * light or dark, and over whatever the screen draws behind it - a painted gradient would only look
 * right on one exact background.
 *
 * The mask is deliberately gentle: it holds most of its opacity through the band and only reaches
 * zero in the outermost few dp, so a row sliding under the status bar or the floating toolbar
 * softens rather than disappearing.
 *
 * Costs one offscreen layer per screen, so it belongs on the container that wraps a list - not on
 * individual items.
 */
fun Modifier.edgeFade(top: Dp = 24.dp, bottom: Dp = 48.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val topPx = top.toPx().coerceAtLeast(0f)
        val bottomPx = bottom.toPx().coerceAtLeast(0f)
        val topBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.35f to Color.Black.copy(alpha = 0.65f),
                1f to Color.Black,
            ),
            startY = 0f,
            endY = topPx,
        )
        val bottomBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Black,
                0.6f to Color.Black.copy(alpha = 0.75f),
                1f to Color.Transparent,
            ),
            startY = size.height - bottomPx,
            endY = size.height,
        )
        onDrawWithContent {
            drawContent()
            // Only the two bands are touched; everything between them keeps its own alpha.
            if (topPx > 0f) {
                drawRect(
                    brush = topBrush,
                    size = Size(size.width, topPx),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (bottomPx > 0f) {
                drawRect(
                    brush = bottomBrush,
                    topLeft = Offset(0f, size.height - bottomPx),
                    size = Size(size.width, bottomPx),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
