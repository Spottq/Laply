package com.flexy.f1live.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Qualifying parts, the break between them, and race control's word on a hold-up. Shared by the
 * Live screen and the Live Update so both name a delay the same way.
 */
object SessionBreak {

    fun isQualifyingLike(kind: SessionKind): Boolean =
        kind == SessionKind.QUALIFYING || kind == SessionKind.SPRINT_QUALIFYING

    /** "Q2", or "SQ2" in sprint qualifying - the names race control uses. */
    fun partLabel(kind: SessionKind, part: Int): String =
        (if (kind == SessionKind.SPRINT_QUALIFYING) "SQ" else "Q") + part

    /** The current part's label, null outside qualifying or when the feed does not say. */
    fun partLabel(state: LiveSessionState): String? =
        state.sessionPart
            ?.takeIf { it in 1..3 && isQualifyingLike(state.sessionKind) }
            ?.let { partLabel(state.sessionKind, it) }

    /**
     * Q1 or Q2 has had its chequered flag and the next part has not started: the feed reads
     * FINISHED until then, however long a delay keeps the cars in the garage.
     */
    fun isBetweenParts(state: LiveSessionState): Boolean =
        state.status == SessionStatus.FINISHED &&
            isQualifyingLike(state.sessionKind) &&
            state.sessionPart in 1..2

    /**
     * The latest race-control message announcing a hold-up ("START OF SQ2 WILL BE DELAYED",
     * "SESSION WILL RESUME AT 16:20"), or null. Only while the session is actually held - between
     * qualifying parts, under a red flag, or not started yet - and only messages since the event
     * that started the hold, so an earlier part's or an earlier red flag's notice does not linger.
     */
    fun delayNotice(state: LiveSessionState): RaceControlMessage? {
        val since: (RaceControlMessage) -> Boolean = when {
            isBetweenParts(state) -> { message -> message.flag.equals("CHEQUERED", ignoreCase = true) }
            state.status == SessionStatus.ABORTED -> { message -> message.flag.equals("RED", ignoreCase = true) }
            state.status == SessionStatus.INACTIVE -> { _ -> false }
            else -> return null
        }
        val messages = state.raceControl
        val start = messages.indexOfLast(since) + 1
        return messages.subList(start, messages.size).lastOrNull { DelayPattern.containsMatchIn(it.message) }
    }

    /**
     * [delayNotice]'s wording with every "AT 20:55" clock time moved from track time into [zone]
     * ("SQ2 WILL START AT 15:55" on a phone in Moscow for Singapore), so a restart time reads
     * right wherever the viewer is. Left as written when the feed gives no track offset.
     */
    fun delayText(state: LiveSessionState, zone: ZoneId = ZoneId.systemDefault()): String? {
        val notice = delayNotice(state) ?: return null
        val offsetMinutes = state.trackUtcOffsetMinutes ?: return notice.message
        val trackOffset = ZoneOffset.ofTotalSeconds(offsetMinutes * 60)
        val sentAt = Instant.ofEpochMilli(notice.utcMillis ?: state.lastUpdateUtcMillis)
        return ClockTimePattern.replace(notice.message) { match ->
            val (hours, minutes) = match.destructured
            val time = runCatching { LocalTime.of(hours.toInt(), minutes.toInt()) }.getOrNull()
                ?: return@replace match.value
            // The announced time is today at the track - or tomorrow, if that is already past.
            var at = sentAt.atOffset(trackOffset).toLocalDate().atTime(time).atOffset(trackOffset)
            if (at.toInstant().isBefore(sentAt.minusSeconds(12 * 60 * 60))) at = at.plusDays(1)
            match.value.substring(0, match.groups[1]!!.range.first - match.range.first) +
                ClockFormat.format(at.atZoneSameInstant(zone))
        }
    }

    /** "AT 20:55" / "AT 20.55" - the clock time is groups 1 and 2. */
    private val ClockTimePattern = Regex("""\bAT\s+(\d{1,2})[:.](\d{2})\b""", RegexOption.IGNORE_CASE)

    private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** "SQ2 delayed", "Restart delayed", "Start delayed" - or null when nothing is held up. */
    fun delayLabel(state: LiveSessionState): String? {
        if (delayNotice(state) == null) return null
        return when {
            isBetweenParts(state) ->
                partLabel(state.sessionKind, (state.sessionPart ?: 1) + 1) + " delayed"
            state.status == SessionStatus.ABORTED -> "Restart delayed"
            else -> "Start delayed"
        }
    }

    private val DelayPattern =
        Regex("""\bDELAY|WILL (?:RE)?START AT|WILL RESUME AT|RESUMPTION""", RegexOption.IGNORE_CASE)
}
