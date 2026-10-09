package com.flexy.f1live.data

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.SessionKind

/**
 * What is left of the drivers' championship: the races and sprints still on the calendar after
 * the standings' round, and how far down the table a driver can still catch the leader with them.
 *
 * A driver is in the fight while their deficit is no bigger than the points left. Level on points
 * the title goes to countback, which this does not try to predict, so a driver who could only draw
 * level still counts.
 */
data class TitleFight(
    val racesLeft: Int,
    val sprintsLeft: Int,
    /** How many drivers from the top of the table can still win the title; at least one. */
    val contenders: Int,
) {
    val pointsLeft: Int get() = racesLeft * RACE_WIN + sprintsLeft * SPRINT_WIN

    /** Only one driver can still finish on top: the leader. */
    val decided: Boolean get() = contenders == 1

    /** "4 races + 1 sprint left · 108 pts to play for"; null once there is nothing left to race. */
    val summary: String?
        get() {
            if (pointsLeft == 0) return null
            val left = listOfNotNull(
                racesLeft.takeIf { it > 0 }?.let { count(it, "race") },
                sprintsLeft.takeIf { it > 0 }?.let { count(it, "sprint") },
            )
            return left.joinToString(" + ") + " left · " + pointsLeft + " pts to play for"
        }

    private fun count(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"

    companion object {
        /** A win is worth 25 in a race and 8 in a sprint; there is no fastest-lap point since 2025. */
        const val RACE_WIN = 25
        const val SPRINT_WIN = 8

        /**
         * Standings published after a sprint carry the same round number as the ones after that
         * weekend's race, so the race keeps counting as still to come for this long after its start.
         */
        private const val SPRINT_WEEKEND_RACE_GRACE_MS = 24 * 60 * 60 * 1000L

        /**
         * Null when there is nothing to go on: no standings, or a calendar that does not reach the
         * standings' round (a different season's, or one cut short), which would make it look as if
         * the season were over.
         */
        fun of(
            drivers: List<DriverStanding>,
            afterRound: Int,
            weekends: List<RaceWeekend>,
            nowUtcMillis: Long,
        ): TitleFight? {
            val table = drivers.sortedBy { it.position }
            val leader = table.firstOrNull() ?: return null
            if (weekends.isEmpty() || weekends.maxOf { it.round } < afterRound) return null

            var races = 0
            var sprints = 0
            for (weekend in weekends) {
                val sprintWeekend = weekend.sessions.any { it.kind == SessionKind.SPRINT }
                for (session in weekend.sessions) {
                    val toCome = when {
                        weekend.round > afterRound -> true
                        weekend.round < afterRound -> false
                        // The standings' own round: only a sprint weekend's race can be missing
                        // from them.
                        session.kind != SessionKind.RACE || !sprintWeekend -> false
                        else -> session.startUtcMillis
                            ?.let { nowUtcMillis < it + SPRINT_WEEKEND_RACE_GRACE_MS }
                            ?: true
                    }
                    if (!toCome) continue
                    when (session.kind) {
                        SessionKind.RACE -> races++
                        SessionKind.SPRINT -> sprints++
                        else -> Unit
                    }
                }
            }

            val pointsLeft = races * RACE_WIN + sprints * SPRINT_WIN
            // The table is in points order, so everyone who can still win sits above everyone who
            // cannot. With nothing left to race, countback has already put the champion first.
            val contenders = if (pointsLeft == 0) {
                1
            } else {
                table.indexOfFirst { it.points + pointsLeft < leader.points }
                    .let { if (it < 0) table.size else it }
            }
            return TitleFight(racesLeft = races, sprintsLeft = sprints, contenders = contenders)
        }
    }
}
