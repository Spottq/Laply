package com.flexy.f1live.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Large-screen support: M3 window width classes, measured on the space the screen actually gets
 * (so split screen and free-form windows count too, and previews with a device spec just work).
 */

/** Start of the MEDIUM width class (unfolded foldables, small tablets, landscape phones). */
val MediumWidthBreakpoint = 600.dp

/** Start of the EXPANDED width class (tablets in landscape). */
val ExpandedWidthBreakpoint = 840.dp

/** The widest a single column of cards gets on a large screen; beyond it the column is centred. */
val MaxColumnWidth = 720.dp

/**
 * Hands [content] the horizontal gutter that centres a column of at most [maxContentWidth] in the
 * available width: zero on phones. Lists add it to their contentPadding (see [plusHorizontal]), so
 * they still scroll from anywhere across the screen, not only over the centred column.
 */
@Composable
fun CenteredColumn(
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = MaxColumnWidth,
    content: @Composable (gutter: Dp) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val gutter = if (constraints.hasBoundedWidth) {
            ((maxWidth - maxContentWidth) / 2).coerceAtLeast(0.dp)
        } else {
            0.dp
        }
        content(gutter)
    }
}

/** This padding with [extra] added on both horizontal sides. */
@Composable
fun PaddingValues.plusHorizontal(extra: Dp): PaddingValues {
    if (extra <= 0.dp) return this
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction) + extra,
        top = calculateTopPadding(),
        end = calculateEndPadding(direction) + extra,
        bottom = calculateBottomPadding(),
    )
}
