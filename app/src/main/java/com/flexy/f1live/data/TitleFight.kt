package com.flexy.f1live.data

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.SessionKind

data class TitleFight(
    val racesLeft: Int,
    val sprintsLeft: Int,
    val contenders: Int,
    val perRace: Int = RACE_WIN,
    val perSprint: Int = SPRINT_WIN,
) {
    val pointsLeft: Int get() = racesLeft * perRace + sprintsLeft * perSprint

    val decided: Boolean get() = contenders == 1

    val summary: String?
        get() = racesLeftText?.let { "$it · $pointsLeftText" }

    val racesLeftText: String?
        get() {
            if (racesLeft == 0 && sprintsLeft == 0) return null
            val left = listOfNotNull(
                racesLeft.takeIf { it > 0 }?.let { count(it, "race") },
                sprintsLeft.takeIf { it > 0 }?.let { count(it, "sprint") },
            )
            return left.joinToString(" + ") + " left"
        }

    val pointsLeftText: String get() = "$pointsLeft pts to play for"

    private fun count(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"

    companion object {
        const val RACE_WIN = 25
        const val SPRINT_WIN = 8

        const val RACE_ONE_TWO = 43
        const val SPRINT_ONE_TWO = 15

        private const val SPRINT_WEEKEND_RACE_GRACE_MS = 24 * 60 * 60 * 1000L

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
