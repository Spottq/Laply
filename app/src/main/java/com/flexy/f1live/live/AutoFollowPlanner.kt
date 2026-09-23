package com.flexy.f1live.live

import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.model.SessionKind

/**
 * One scheduled session the auto-follow machinery can wake up for. [label] is what the waiting
 * notification shows before the feed has published anything ("Italian Grand Prix · Race").
 */
data class SessionTarget(
    val startUtcMillis: Long,
    val kind: SessionKind,
    val label: String,
)

/**
 * Pure (Android-free) timing rules for "auto-follow sessions", kept apart from the alarm and
 * service glue in [AutoFollow] so they can be unit-tested without a device.
 *
 * The model is a window per session: the alarm wakes the app [LEAD_MS] before the scheduled start,
 * the service then waits up to [WAIT_AFTER_START_MS] past the start for the feed to go live
 * (formation laps, rain delays and late feeds all push "live" past the published time), and the
 * whole [expectedDurationMs] window is what an app launch treats as "a session is on right now".
 */
object AutoFollowPlanner {

    /** How early the alarm fires: enough to connect, not so early the notification lingers. */
    const val LEAD_MS = 3L * 60L * 1000L

    /** How long past the scheduled start a woken service keeps waiting for the session to go live. */
    const val WAIT_AFTER_START_MS = 30L * 60L * 1000L

    /**
     * Minimum wait for a service started in the middle of a session window (app launch, feed went
     * live). Long enough for the SignalR negotiate plus the ESPN fallback's 20 s timeout.
     */
    const val PROBE_MS = 3L * 60L * 1000L

    /** Suppression after "Stop" in the notification when the session it belonged to is unknown. */
    const val DEFAULT_SUPPRESS_MS = 3L * 60L * 60L * 1000L

    /**
     * Generous upper bounds on how long a session occupies the track, red flags included. They only
     * decide when an app launch still counts as "during the session" and how long a notification
     * dismissal holds; the service itself stops on the feed's own end-of-session status.
     */
    fun expectedDurationMs(kind: SessionKind): Long = when (kind) {
        SessionKind.PRACTICE1, SessionKind.PRACTICE2, SessionKind.PRACTICE3 -> 90L
        SessionKind.QUALIFYING -> 90L
        SessionKind.SPRINT_QUALIFYING -> 75L
        SessionKind.SPRINT -> 75L
        SessionKind.RACE -> 180L
        SessionKind.UNKNOWN -> 120L
    } * 60L * 1000L

    /** Every session with a known start time, in start order. */
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

    /** A pending alarm: fire at [triggerAtMillis] on behalf of [target]. */
    data class Alarm(val triggerAtMillis: Long, val target: SessionTarget)

    /**
     * The next alarm to arm: the earliest session whose wake-up time (`start - LEAD_MS`) is still
     * strictly in the future. Strictly, because this runs again from the alarm that just fired - at
     * that instant the session it fired for must not be picked a second time.
     *
     * A session whose wake-up time has already passed is deliberately not re-armed for "now": that
     * case (a reboot three minutes before a start) is picked up by [currentWindow] on the next app
     * launch instead of risking an alarm that re-arms itself in a loop.
     */
    fun nextAlarm(targets: List<SessionTarget>, now: Long): Alarm? =
        targets
            .filter { it.startUtcMillis - LEAD_MS > now }
            .minByOrNull { it.startUtcMillis }
            ?.let { Alarm(triggerAtMillis = it.startUtcMillis - LEAD_MS, target = it) }

    /**
     * The session whose window contains [now]: from [LEAD_MS] before its start until
     * [expectedDurationMs] after it. When windows overlap (they should not) the latest start wins,
     * since that is the one the feed is about to switch to.
     */
    fun currentWindow(targets: List<SessionTarget>, now: Long): SessionTarget? =
        targets
            .filter { now >= it.startUtcMillis - LEAD_MS && now < windowEnd(it) }
            .maxByOrNull { it.startUtcMillis }

    /** End of the "this session is on" window. */
    fun windowEnd(target: SessionTarget): Long = target.startUtcMillis + expectedDurationMs(target.kind)

    /**
     * When a service that has not seen the session go live gives up. Relative to the scheduled
     * start when there is one, but never less than [PROBE_MS] from [now], so a service started late
     * in a window still gets a fair chance to connect.
     */
    fun waitDeadline(target: SessionTarget?, now: Long): Long {
        val probe = now + PROBE_MS
        val scheduled = target?.let { it.startUtcMillis + WAIT_AFTER_START_MS } ?: return probe
        return maxOf(scheduled, probe)
    }

    /**
     * Until when auto-starts stay off after the user tapped "Stop" in the notification: the end of
     * the session it was showing, or [DEFAULT_SUPPRESS_MS] when that session is unknown. Long enough
     * to cover the rest of the session, short enough never to swallow the next one of the weekend.
     */
    fun suppressUntil(target: SessionTarget?, now: Long): Long =
        target?.let { maxOf(windowEnd(it), now) } ?: (now + DEFAULT_SUPPRESS_MS)
}
