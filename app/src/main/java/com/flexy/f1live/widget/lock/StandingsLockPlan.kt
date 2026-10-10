package com.flexy.f1live.widget.lock

import com.flexy.f1live.widget.WidgetPlanner
import com.flexy.f1live.widget.standings.StandingsWidgetPlanner
import com.flexy.f1live.widget.standings.TablePlan

object StandingsLockPlan {

    const val MAX_ROWS = 5
    const val MAX_CHASERS = 2
    const val MAX_LEADER_LINES = 2

    private const val PADDING_DP = 20f
    private const val OVERLINE_SP = 12f
    private const val ROW_SP = 15.5f
    private const val ROW_GAP_DP = 2f
    private const val LINE_DP = 10f
    private const val LEADER_SP = 56f
    private const val LEADER_DP = 5f
    private const val SMALL_LINE_SP = 13f
    private const val SMALL_LINE_GAP_DP = 4f

    fun table(heightDp: Int, fontScale: Float, entries: Int, contenders: Int?): TablePlan {
        val scale = fontScale.coerceAtLeast(0.5f)
        val space = heightDp - PADDING_DP - OVERLINE_SP * scale
        return StandingsWidgetPlanner.tablePlan(
            space = space,
            rowHeight = ROW_SP * scale + ROW_GAP_DP,
            lineHeight = LINE_DP,
            entries = entries,
            contenders = contenders,
            maxRows = MAX_ROWS,
        )
    }

    fun leaderLines(heightDp: Int, fontScale: Float): Int {
        val scale = fontScale.coerceAtLeast(0.5f)
        val space = heightDp - PADDING_DP - LEADER_DP - LEADER_SP * scale
        return WidgetPlanner.rowsThatFit(space, SMALL_LINE_SP * scale + SMALL_LINE_GAP_DP, 0f)
            .coerceIn(0, MAX_LEADER_LINES)
    }
}
