package com.flexy.f1live.model

import com.flexy.f1live.data.LiveStateParser
import com.flexy.f1live.live.LiveNotificationBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SessionBreakTest {

    private val moscow = ZoneId.of("Europe/Moscow")

    private fun at(hhmm: String): Long =
        Instant.parse("2026-10-09T${hhmm}:00Z").toEpochMilli()

    private fun message(utc: String, text: String, flag: String? = null) =
        RaceControlMessage(utcMillis = at(utc), category = "Other", message = text, flag = flag)

    private val sqBreak = LiveSessionState.EMPTY.copy(
        meetingCountry = "Singapore",
        sessionName = "Sprint Qualifying",
        sessionKind = SessionKind.SPRINT_QUALIFYING,
        sessionPart = 1,
        status = SessionStatus.FINISHED,
        source = LiveSource.F1_LIVE_TIMING,
        trackUtcOffsetMinutes = 8 * 60,
        raceControl = listOf(
            message("12:40", "START OF SQ1 WILL BE DELAYED"),
            message("12:52", "CHEQUERED FLAG", flag = "CHEQUERED"),
            message("12:55", "START OF SQ2 WILL BE DELAYED"),
        ),
    )

    @Test
    fun `the break after SQ1 names SQ2 and race control's notice`() {
        assertTrue(SessionBreak.isBetweenParts(sqBreak))
        assertEquals("SQ2 delayed", SessionBreak.delayLabel(sqBreak))
        assertEquals("START OF SQ2 WILL BE DELAYED", SessionBreak.delayText(sqBreak, moscow))
    }

    @Test
    fun `the whole notification title becomes race control's announcement`() {
        assertEquals("START OF SQ2 WILL BE DELAYED", LiveNotificationBuilder.title(sqBreak))
    }

    @Test
    fun `an announced restart time is moved from track time to the viewer's`() {
        val timed = sqBreak.copy(
            raceControl = sqBreak.raceControl + message("13:00", "SQ2 WILL START AT 21:15"),
        )
        assertEquals("SQ2 WILL START AT 16:15", SessionBreak.delayText(timed, moscow))
    }

    @Test
    fun `a time is left as written when the feed gives no track offset`() {
        val timed = sqBreak.copy(
            trackUtcOffsetMinutes = null,
            raceControl = sqBreak.raceControl + message("13:00", "SQ2 WILL START AT 21:15"),
        )
        assertEquals("SQ2 WILL START AT 21:15", SessionBreak.delayText(timed, moscow))
    }

    @Test
    fun `a notice from before the last chequered flag does not carry over`() {
        val noDelay = sqBreak.copy(raceControl = sqBreak.raceControl.dropLast(1))
        assertNull(SessionBreak.delayNotice(noDelay))
        assertEquals("Singapore · Sprint Qualifying · SQ1 FINISHED", LiveNotificationBuilder.title(noDelay))
    }

    @Test
    fun `a running session shows no delay`() {
        val running = sqBreak.copy(sessionPart = 2, status = SessionStatus.STARTED)
        assertNull(SessionBreak.delayLabel(running))
        assertFalse(LiveNotificationBuilder.title(running).contains("DELAYED"))
    }

    @Test
    fun `a delayed race start and a red flag restart are announced too`() {
        val race = LiveSessionState.EMPTY.copy(
            sessionName = "Race",
            sessionKind = SessionKind.RACE,
            status = SessionStatus.INACTIVE,
            raceControl = listOf(message("11:50", "THE START OF THE RACE WILL BE DELAYED")),
        )
        assertEquals("Start delayed", SessionBreak.delayLabel(race))
        assertEquals("THE START OF THE RACE WILL BE DELAYED", LiveNotificationBuilder.title(race))

        val redFlag = race.copy(
            status = SessionStatus.ABORTED,
            trackFlag = TrackFlag.RED,
            raceControl = listOf(
                message("12:10", "RACE WILL RESUME AT 20:00"),
                message("12:20", "GREEN LIGHT - PIT EXIT OPEN"),
                message("12:40", "RED FLAG", flag = "RED"),
                message("12:45", "RESUMPTION TIME WILL BE ANNOUNCED"),
            ),
        )
        assertEquals("RESUMPTION TIME WILL BE ANNOUNCED", SessionBreak.delayNotice(redFlag)?.message)
        assertEquals("Restart delayed", SessionBreak.delayLabel(redFlag))
    }

    @Test
    fun `GmtOffset parses with either sign`() {
        assertEquals(480, LiveStateParser.utcOffsetMinutesOf("08:00:00"))
        assertEquals(-240, LiveStateParser.utcOffsetMinutesOf("-04:00:00"))
        assertEquals(330, LiveStateParser.utcOffsetMinutesOf("05:30:00"))
        assertNull(LiveStateParser.utcOffsetMinutesOf("soon"))
    }
}
