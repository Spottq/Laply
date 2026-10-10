package com.flexy.f1live.widget.standings

import com.flexy.f1live.data.ConstructorStanding
import com.flexy.f1live.data.DriverStanding
import com.flexy.f1live.data.constructorColorHex
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.widget.WidgetPlanner

/**
 * One line of a standings widget, a driver or a team, with every label the layouts pick from.
 */
data class StandingsEntry(
    val position: Int,
    /** "O. Piastri" or "McLaren": the wide tables. */
    val name: String,
    /** "Piastri": the title-fight tiles. A team's is its name. */
    val lastName: String,
    /** "PIA": the narrow tables. A team's is its name. */
    val code: String,
    /** The driver's team, or the team itself. */
    val team: String,
    val points: Double,
    val wins: Int,
    /** Team livery colour, 6-digit hex without '#'. */
    val colorHex: String,
)

/** Which rows a table widget draws in its height, and where the title line goes among them. */
data class TablePlan(
    val rows: Int,
    /** Rows above the "out of reach" line; null when the line is not drawn. */
    val lineAfter: Int?,
)

/**
 * Pure (Android-free) rules behind the standings widgets, kept apart from the Glance code so they
 * can be unit-tested: the rows that fit, the labels, and when the table is worth fetching again.
 *
 * The standings only change after a race or a sprint, and Jolpica publishes them some time after
 * the flag, sometimes hours later. So the widgets check again a few times after every race and
 * sprint ([CHECK_DELAYS_MS]) and otherwise once a day; between those moments they show the stored
 * table without touching the network.
 */
object StandingsWidgetPlanner {

    const val HOUR_MS = 60L * 60L * 1000L
    const val DAY_MS = 24L * HOUR_MS

    /** After a race or sprint ends, the table is fetched again this long after it. */
    val CHECK_DELAYS_MS = listOf(1L, 3L, 6L, 12L, 24L).map { it * HOUR_MS }

    /** A failed or premature check is not repeated sooner than this. */
    const val MIN_RECHECK_MS = 30L * 60L * 1000L

    /** Rows one table widget lists at most: a tall 4x5, or the whole field of teams. */
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

    /** "Haas F1 Team" -> "Haas": the suffix only costs width in a widget. */
    fun teamName(name: String): String = name.removeSuffix(" F1 Team").trim()

    /** "242" rather than "242.0", but "18.5" keeps its half point. */
    fun formatPoints(points: Double): String =
        if (points == points.toLong().toDouble()) points.toLong().toString() else points.toString()

    /** "−22" behind the leader (a real minus sign), "0" when level. */
    fun gapText(leaderPoints: Double, points: Double): String {
        val gap = leaderPoints - points
        return if (gap <= 0.0) "0" else "−" + formatPoints(gap)
    }

    /**
     * The rows that fit in [space] (dp), each [rowHeight] tall, out of [entries], and the title
     * line ([lineHeight] tall) after the first [contenders] of them. The line is drawn only with a
     * row under it - one at the very bottom would say nothing - and when it is left out every row
     * shown is still a contender, so the table never suggests someone can win who cannot.
     */
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

    /** When each race and sprint of [weekends] ends, as the widget planner times them. */
    fun scoringSessionEnds(weekends: List<RaceWeekend>): List<Long> =
        weekends.flatMap { weekend ->
            weekend.sessions
                .filter { it.kind == SessionKind.RACE || it.kind == SessionKind.SPRINT }
                .mapNotNull { session -> session.startUtcMillis?.plus(WidgetPlanner.durationMs(session.kind)) }
        }

    private fun checkpoints(weekends: List<RaceWeekend>): List<Long> =
        scoringSessionEnds(weekends).flatMap { end -> CHECK_DELAYS_MS.map { end + it } }

    /**
     * True when the stored table, fetched at [fetchedAt] (0: never), may be out of date at [now]:
     * a check after a race or sprint has come up since, or it is a day old.
     */
    fun isDue(fetchedAt: Long, weekends: List<RaceWeekend>, now: Long): Boolean {
        // Never fetched, or the clock was set back past the fetch.
        if (fetchedAt <= 0L || fetchedAt > now) return true
        if (now - fetchedAt >= DAY_MS) return true
        return checkpoints(weekends).any { it > fetchedAt && it <= now }
    }

    /**
     * For a finished season's table, fetched at [fetchedAt]: true while it may still lack the last
     * results, i.e. it was fetched before the checks after the final race and sprint were done.
     * A table fetched after those is final and never needs the network again.
     */
    fun missesResults(fetchedAt: Long, weekends: List<RaceWeekend>): Boolean =
        fetchedAt <= 0L || checkpoints(weekends).any { it > fetchedAt }

    /**
     * The next moment worth checking: the next check after a race or sprint, or a day after the
     * last fetch, whichever comes first - but never sooner than [MIN_RECHECK_MS] from [now], so a
     * fetch that keeps failing (offline) is retried calmly rather than in a loop.
     */
    fun nextCheckAt(fetchedAt: Long, weekends: List<RaceWeekend>, now: Long): Long {
        val daily = if (fetchedAt <= 0L || fetchedAt > now) now else fetchedAt + DAY_MS
        val nextCheckpoint = checkpoints(weekends).filter { it > now }.minOrNull() ?: Long.MAX_VALUE
        return maxOf(minOf(daily, nextCheckpoint), now + MIN_RECHECK_MS)
    }
}
