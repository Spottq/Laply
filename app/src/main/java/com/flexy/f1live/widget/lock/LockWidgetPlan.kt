package com.flexy.f1live.widget.lock

import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.widget.Countdown
import com.flexy.f1live.widget.WidgetEntry
import com.flexy.f1live.widget.WidgetPlanner

sealed interface LockWidgetPlan {
    data object Empty : LockWidgetPlan

    data class Live(val entry: WidgetEntry) : LockWidgetPlan

    data class Upcoming(val entry: WidgetEntry, val countdown: Countdown) : LockWidgetPlan

    companion object {
        const val ENTRIES = 2

        fun of(entries: List<WidgetEntry>, now: Long): LockWidgetPlan {
            val first = entries.firstOrNull() ?: return Empty
            if (first.isLive(now)) return Live(first)
            return Upcoming(first, WidgetPlanner.countdown(first, now))
        }
    }
}

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
