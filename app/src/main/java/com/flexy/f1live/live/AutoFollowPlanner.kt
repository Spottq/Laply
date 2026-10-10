package com.flexy.f1live.live

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.SessionKind

data class SessionTarget(
    val startUtcMillis: Long,
    val kind: SessionKind,
    val label: String,
)

object AutoFollowPlanner {

    const val LEAD_MS = 3L * 60L * 1000L

    const val WAIT_AFTER_START_MS = 30L * 60L * 1000L

    const val PROBE_MS = 3L * 60L * 1000L

    const val DEFAULT_SUPPRESS_MS = 3L * 60L * 60L * 1000L

    fun expectedDurationMs(kind: SessionKind): Long = when (kind) {
        SessionKind.PRACTICE1, SessionKind.PRACTICE2, SessionKind.PRACTICE3 -> 90L
        SessionKind.QUALIFYING -> 90L
        SessionKind.SPRINT_QUALIFYING -> 75L
        SessionKind.SPRINT -> 75L
        SessionKind.RACE -> 180L
        SessionKind.UNKNOWN -> 120L
    } * 60L * 1000L

    fun targets(weekends: List<RaceWeekend>): List<SessionTarget> =
        weekends
            .flatMap { weekend ->
                weekend.sessions.mapNotNull { session ->
                    val start = session.startUtcMillis ?: return@mapNotNull null
                    SessionTarget(
                        startUtcMillis = start,
                        kind = session.kind,
                        label = listOf(weekend.name, session.name)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                    )
                }
            }
            .sortedBy { it.startUtcMillis }

    data class Alarm(val triggerAtMillis: Long, val target: SessionTarget)

    fun nextAlarm(targets: List<SessionTarget>, now: Long): Alarm? =
        targets
            .filter { it.startUtcMillis - LEAD_MS > now }
            .minByOrNull { it.startUtcMillis }
            ?.let { Alarm(triggerAtMillis = it.startUtcMillis - LEAD_MS, target = it) }

    fun currentWindow(targets: List<SessionTarget>, now: Long): SessionTarget? =
        targets
            .filter { now >= it.startUtcMillis - LEAD_MS && now < windowEnd(it) }
            .maxByOrNull { it.startUtcMillis }

    fun windowEnd(target: SessionTarget): Long = target.startUtcMillis + expectedDurationMs(target.kind)

    fun waitDeadline(target: SessionTarget?, now: Long): Long {
        val probe = now + PROBE_MS
        val scheduled = target?.let { it.startUtcMillis + WAIT_AFTER_START_MS } ?: return probe
        return maxOf(scheduled, probe)
    }

    fun suppressUntil(target: SessionTarget?, now: Long): Long =
        target?.let { maxOf(windowEnd(it), now) } ?: (now + DEFAULT_SUPPRESS_MS)
}
