package com.flexy.f1live.widget.lock

import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.widget.Countdown
import com.flexy.f1live.widget.WidgetEntry
import com.flexy.f1live.widget.WidgetPlanner

/**
 * What the 2x1 lock-screen widget shows, decided without Android so it can be unit-tested. The
 * session list, "live" window and countdown rules are the home-screen widgets' ([WidgetPlanner]);
 * this only picks the one session a 2x1 has room for.
 */
sealed interface LockWidgetPlan {
    /** No calendar, or nothing left in it. */
    data object Empty : LockWidgetPlan

    /** [entry] is running now: "Azerbaijan GP" over "LIVE · FP1". */
    data class Live(val entry: WidgetEntry) : LockWidgetPlan

    /** [entry] is next: "FP1 · Azerbaijan GP" over [countdown]. */
    data class Upcoming(val entry: WidgetEntry, val countdown: Countdown) : LockWidgetPlan

    companion object {
        /** Sessions to look at: a running one, and the one after it for the refresh timing. */
        const val ENTRIES = 2

        fun of(entries: List<WidgetEntry>, now: Long): LockWidgetPlan {
            val first = entries.firstOrNull() ?: return Empty
            if (first.isLive(now)) return Live(first)
            return Upcoming(first, WidgetPlanner.countdown(first, now))
        }
    }
}

/** The F1 timing-screen abbreviation of a session kind; null for UNKNOWN (use its name). */
enum class LockSessionLabel { FP1, FP2, FP3, SQ, SPRINT, QUALI, RACE }

fun lockSessionLabel(kind: SessionKind): LockSessionLabel? = when (kind) {
    SessionKind.PRACTICE1 -> LockSessionLabel.FP1
    SessionKind.PRACTICE2 -> LockSessionLabel.FP2
    SessionKind.PRACTICE3 -> LockSessionLabel.FP3
    SessionKind.SPRINT_QUALIFYING -> LockSessionLabel.SQ
    SessionKind.SPRINT -> LockSessionLabel.SPRINT
    SessionKind.QUALIFYING -> LockSessionLabel.QUALI
    SessionKind.RACE -> LockSessionLabel.RACE
    SessionKind.UNKNOWN -> null
}
