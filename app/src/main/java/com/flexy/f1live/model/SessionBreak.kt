package com.flexy.f1live.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object SessionBreak {

    fun isQualifyingLike(kind: SessionKind): Boolean =
        kind == SessionKind.QUALIFYING || kind == SessionKind.SPRINT_QUALIFYING

    fun partLabel(kind: SessionKind, part: Int): String =
        (if (kind == SessionKind.SPRINT_QUALIFYING) "SQ" else "Q") + part

    fun partLabel(state: LiveSessionState): String? =
        state.sessionPart
            ?.takeIf { it in 1..3 && isQualifyingLike(state.sessionKind) }
            ?.let { partLabel(state.sessionKind, it) }

    fun isBetweenParts(state: LiveSessionState): Boolean =
        state.status == SessionStatus.FINISHED &&
            isQualifyingLike(state.sessionKind) &&
            state.sessionPart in 1..2

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

    fun delayText(state: LiveSessionState, zone: ZoneId = ZoneId.systemDefault()): String? {
        val notice = delayNotice(state) ?: return null
        val offsetMinutes = state.trackUtcOffsetMinutes ?: return notice.message
        val trackOffset = ZoneOffset.ofTotalSeconds(offsetMinutes * 60)
        val sentAt = Instant.ofEpochMilli(notice.utcMillis ?: state.lastUpdateUtcMillis)
        return ClockTimePattern.replace(notice.message) { match ->
            val (hours, minutes) = match.destructured
            val time = runCatching { LocalTime.of(hours.toInt(), minutes.toInt()) }.getOrNull()
                ?: return@replace match.value
            var at = sentAt.atOffset(trackOffset).toLocalDate().atTime(time).atOffset(trackOffset)
            if (at.toInstant().isBefore(sentAt.minusSeconds(12 * 60 * 60))) at = at.plusDays(1)
            match.value.substring(0, match.groups[1]!!.range.first - match.range.first) +
                ClockFormat.format(at.atZoneSameInstant(zone))
        }
    }

    private val ClockTimePattern = Regex("""\bAT\s+(\d{1,2})[:.](\d{2})\b""", RegexOption.IGNORE_CASE)

    private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

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
