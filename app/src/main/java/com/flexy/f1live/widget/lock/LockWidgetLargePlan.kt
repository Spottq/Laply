package com.flexy.f1live.widget.lock

import com.flexy.f1live.widget.WidgetEntry
import com.flexy.f1live.widget.WidgetPlanner

object LockWidgetLargePlan {

    const val MAX_ROWS = 2

    const val ENTRIES = 1 + MAX_ROWS

    const val DEFAULT_WIDTH_DP = 150
    const val DEFAULT_HEIGHT_DP = 133

    private const val FIXED_TEXT_SP = 70f
    private const val FIXED_DP = 29f

    private const val ROW_SP = 13f
    private const val ROW_GAP_DP = 2f
    private const val FIRST_ROW_EXTRA_DP = 3f

    fun rowsThatFit(heightDp: Int, fontScale: Float = 1f): Int {
        val scale = fontScale.coerceAtLeast(0.5f)
        val space = heightDp - FIXED_TEXT_SP * scale - FIXED_DP - FIRST_ROW_EXTRA_DP
        return WidgetPlanner.rowsThatFit(space, ROW_SP * scale, ROW_GAP_DP).coerceIn(0, MAX_ROWS)
    }

    fun rows(entries: List<WidgetEntry>, count: Int): List<WidgetEntry> =
        entries.drop(1).take(count.coerceIn(0, MAX_ROWS))
}
