package com.flexy.f1live.widget.lock

import com.flexy.f1live.widget.WidgetEntry
import com.flexy.f1live.widget.WidgetPlanner

/**
 * What the 2x2 lock-screen widget shows besides the 2x1's headline ([LockWidgetPlan]): the
 * sessions after it, as many as fit. Android-free so it can be unit-tested.
 */
object LockWidgetLargePlan {

    /** Most later sessions listed under the countdown. */
    const val MAX_ROWS = 2

    /** Sessions to load and time refreshes by: the headline and the [MAX_ROWS] after it. */
    const val ENTRIES = 1 + MAX_ROWS

    /** Lock-screen 2x2 slot when the host reports no size (Samsung's own 2x2s: ~118-150 x 131-133 dp). */
    const val DEFAULT_WIDTH_DP = 150
    const val DEFAULT_HEIGHT_DP = 133

    /**
     * widget_lock_2x2.xml, measured: the text of the fixed part (overline 10 sp, title 15 sp,
     * session 12 sp, countdown 22 sp; ~1.17 line height without font padding) in sp, and its
     * padding and margins in dp.
     */
    private const val FIXED_TEXT_SP = 70f
    private const val FIXED_DP = 29f

    /** One 11 sp row; the gap between rows; the extra margin above the first row. */
    private const val ROW_SP = 13f
    private const val ROW_GAP_DP = 2f
    private const val FIRST_ROW_EXTRA_DP = 3f

    /**
     * Rows that fit a widget [heightDp] tall at the system [fontScale], 0..[MAX_ROWS]: a row
     * that would not fit whole is not shown (the column is centred, so overflow would clip the
     * overline too).
     */
    fun rowsThatFit(heightDp: Int, fontScale: Float = 1f): Int {
        val scale = fontScale.coerceAtLeast(0.5f)
        val space = heightDp - FIXED_TEXT_SP * scale - FIXED_DP - FIRST_ROW_EXTRA_DP
        return WidgetPlanner.rowsThatFit(space, ROW_SP * scale, ROW_GAP_DP).coerceIn(0, MAX_ROWS)
    }

    /** The sessions listed under the headline (which is always [entries]' first), at most [count]. */
    fun rows(entries: List<WidgetEntry>, count: Int): List<WidgetEntry> =
        entries.drop(1).take(count.coerceIn(0, MAX_ROWS))
}
