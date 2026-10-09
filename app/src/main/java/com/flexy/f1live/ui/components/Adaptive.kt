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

val MediumWidthBreakpoint = 600.dp

val ExpandedWidthBreakpoint = 840.dp

val MaxColumnWidth = 720.dp

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
