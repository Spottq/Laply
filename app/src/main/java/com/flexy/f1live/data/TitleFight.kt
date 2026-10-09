package com.flexy.f1live.data

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.SessionKind

/**
 * What is left of a championship: the races and sprints still on the calendar after the
 * standings' round, and how far down the table a driver ([of]) or a team ([ofConstructors]) can
 * still catch the leader with them.
 *
 * An entry is in the fight while its deficit is no bigger than the points left. Level on points
 * the title goes to countback, which this does not try to predict, so one that could only draw
 * level still counts.
 */
data class TitleFight(
    val racesLeft: Int,
    val sprintsLeft: Int,
    /** How many entries from the top of the table can still win the title; at least one. */
    val contenders: Int,
    /** The most one entry can score in a race and in a sprint: a win, or a team's one-two. */
    val perRace: Int = RACE_WIN,
    val perSprint: Int = SPRINT_WIN,
) {
    val pointsLeft: Int get() = racesLeft * perRace + sprintsLeft * perSprint

    /** Only one entry can still finish on top: the leader. */
    val decided: Boolean get() = contenders == 1

    /** "4 races + 1 sprint left · 108 pts to play for"; null once there is nothing left to race. */
    val summary: String?
        get() = racesLeftText?.let { "$it · $pointsLeftText" }

    /** "4 races + 1 sprint left"; null once there is nothing left to race. */
    val racesLeftText: String?
        get() {
            if (racesLeft == 0 && sprintsLeft == 0) return null
            val left = listOfNotNull(
                racesLeft.takeIf { it > 0 }?.let { count(it, "race") },
                sprintsLeft.takeIf { it > 0 }?.let { count(it, "sprint") },
            )
            return left.joinToString(" + ") + " left"
        }

    /** "108 pts to play for". */
    val pointsLeftText: String get() = "$pointsLeft pts to play for"

    private fun count(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"

    companion object {
        /** A win is worth 25 in a race and 8 in a sprint; there is no fastest-lap point since 2025. */
        const val RACE_WIN = 25
        const val SPRINT_WIN = 8

        /** A team's best weekend is a one-two: 25 + 18 in a race, 8 + 7 in a sprint. */
        const val RACE_ONE_TWO = 43
        const val SPRINT_ONE_TWO = 15

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
        ): TitleFight? = fromPoints(
            table = drivers.sortedBy { it.position }.map { it.points },
            afterRound = afterRound,
            weekends = weekends,
            nowUtcMillis = nowUtcMillis,
            perRace = RACE_WIN,
            perSprint = SPRINT_WIN,
        )

        /** The constructors' fight: a team scores with both its cars. Null as for [of]. */
        fun ofConstructors(
            teams: List<ConstructorStanding>,
            afterRound: Int,
            weekends: List<RaceWeekend>,
            nowUtcMillis: Long,
        ): TitleFight? = fromPoints(
            table = teams.sortedBy { it.position }.map { it.points },
            afterRound = afterRound,
            weekends = weekends,
            nowUtcMillis = nowUtcMillis,
            perRace = RACE_ONE_TWO,
            perSprint = SPRINT_ONE_TWO,
        )

        /** [table]: everyone's points, in table order. */
        private fun fromPoints(
            table: List<Double>,
            afterRound: Int,
            weekends: List<RaceWeekend>,
            nowUtcMillis: Long,
            perRace: Int,
            perSprint: Int,
        ): TitleFight? {
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

            val pointsLeft = races * perRace + sprints * perSprint
            // The table is in points order, so everyone who can still win sits above everyone who
            // cannot. With nothing left to race, countback has already put the champion first.
            val contenders = if (pointsLeft == 0) {
                1
            } else {
                table.indexOfFirst { it + pointsLeft < leader }
                    .let { if (it < 0) table.size else it }
            }
            return TitleFight(
                racesLeft = races,
                sprintsLeft = sprints,
                contenders = contenders,
                perRace = perRace,
                perSprint = perSprint,
            )
        }
    }
}
