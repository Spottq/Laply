package com.flexy.f1live.widget.standings

import com.flexy.f1live.data.ConstructorStanding
import com.flexy.f1live.data.DriverStanding
import com.flexy.f1live.data.constructorColorHex
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.widget.WidgetPlanner

data class StandingsEntry(
    val position: Int,
    val name: String,
    val lastName: String,
    val code: String,
    val team: String,
    val points: Double,
    val wins: Int,
    val colorHex: String,
)

data class TablePlan(
    val rows: Int,
    val lineAfter: Int?,
)

object StandingsWidgetPlanner {

    const val HOUR_MS = 60L * 60L * 1000L
    const val DAY_MS = 24L * HOUR_MS

    val CHECK_DELAYS_MS = listOf(1L, 3L, 6L, 12L, 24L).map { it * HOUR_MS }

    const val MIN_RECHECK_MS = 30L * 60L * 1000L

    const val MAX_ROWS = 20

    fun driverEntries(drivers: List<DriverStanding>): List<StandingsEntry> =
        drivers.sortedBy { it.position }.map { driver ->
            StandingsEntry(
                position = driver.position,
                name = driver.shortName,
                lastName = driver.lastName.ifBlank { driver.code },
                code = driver.code.ifBlank { driver.lastName.take(3).uppercase() },
                team = teamName(driver.constructorName),
                points = driver.points,
                wins = driver.wins,
                colorHex = constructorColorHex(driver.constructorId),
            )
        }

    fun teamEntries(teams: List<ConstructorStanding>): List<StandingsEntry> =
        teams.sortedBy { it.position }.map { team ->
            val name = teamName(team.name)
            StandingsEntry(
                position = team.position,
                name = name,
                lastName = name,
                code = name,
                team = name,
                points = team.points,
                wins = team.wins,
                colorHex = constructorColorHex(team.constructorId),
            )
        }

    fun teamName(name: String): String = name.removeSuffix(" F1 Team").trim()

    fun formatPoints(points: Double): String =
        if (points == points.toLong().toDouble()) points.toLong().toString() else points.toString()

    fun gapText(leaderPoints: Double, points: Double): String {
        val gap = leaderPoints - points
        return if (gap <= 0.0) "0" else "−" + formatPoints(gap)
    }

    fun tablePlan(
        space: Float,
        rowHeight: Float,
        lineHeight: Float,
        entries: Int,
        contenders: Int?,
        maxRows: Int = MAX_ROWS,
    ): TablePlan {
        fun fit(height: Float): Int =
            if (rowHeight <= 0f || height < rowHeight) 0 else minOf((height / rowHeight).toInt(), entries, maxRows)

        val withoutLine = fit(space)
        if (contenders == null || contenders <= 0 || contenders >= withoutLine) {
            return TablePlan(rows = withoutLine, lineAfter = null)
        }
        val withLine = fit(space - lineHeight)
        return if (contenders < withLine) {
            TablePlan(rows = withLine, lineAfter = contenders)
        } else {
            TablePlan(rows = withLine, lineAfter = null)
        }
    }

    // ------------------------------------------------------------------ refresh

    fun scoringSessionEnds(weekends: List<RaceWeekend>): List<Long> =
        weekends.flatMap { weekend ->
            weekend.sessions
                .filter { it.kind == SessionKind.RACE || it.kind == SessionKind.SPRINT }
                .mapNotNull { session -> session.startUtcMillis?.plus(WidgetPlanner.durationMs(session.kind)) }
        }

    private fun checkpoints(weekends: List<RaceWeekend>): List<Long> =
        scoringSessionEnds(weekends).flatMap { end -> CHECK_DELAYS_MS.map { end + it } }

    fun isDue(fetchedAt: Long, weekends: List<RaceWeekend>, now: Long): Boolean {
        if (fetchedAt <= 0L || fetchedAt > now) return true
        if (now - fetchedAt >= DAY_MS) return true
        return checkpoints(weekends).any { it > fetchedAt && it <= now }
    }

    fun missesResults(fetchedAt: Long, weekends: List<RaceWeekend>): Boolean =
        fetchedAt <= 0L || checkpoints(weekends).any { it > fetchedAt }

    fun nextCheckAt(fetchedAt: Long, weekends: List<RaceWeekend>, now: Long): Long {
        val daily = if (fetchedAt <= 0L || fetchedAt > now) now else fetchedAt + DAY_MS
        val nextCheckpoint = checkpoints(weekends).filter { it > now }.minOrNull() ?: Long.MAX_VALUE
        return maxOf(minOf(daily, nextCheckpoint), now + MIN_RECHECK_MS)
    }
}
