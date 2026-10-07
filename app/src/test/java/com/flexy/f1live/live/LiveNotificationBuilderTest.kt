package com.flexy.f1live.live

import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.model.TrackFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Live Update is a `Notification.ProgressStyle`, which renders **one** line of body text; these
 * tests pin the compact layout that fits three drivers into it, the status-bar chip and the race
 * progress bar.
 */
class LiveNotificationBuilderTest {

    private fun driver(
        position: Int,
        tla: String,
        shortName: String,
        gap: String = "",
        best: String = "",
        fastestLap: Boolean = false,
        interval: String = "",
    ) = DriverTiming(
        position = position,
        racingNumber = position.toString(),
        tla = tla,
        firstName = "",
        lastName = "",
        shortName = shortName,
        teamName = "",
        teamColorHex = null,
        headshotUrl = null,
        countryCode = null,
        bestLapTime = best,
        lastLapTime = "",
        gapToLeader = gap,
        interval = interval,
        sectors = emptyList(),
        inPit = false,
        pitOut = false,
        retired = false,
        stopped = false,
        knockedOut = false,
        numberOfLaps = 0,
        numberOfPitStops = 0,
        tyreCompound = null,
        fastestLap = fastestLap,
    )

    private val race = LiveSessionState.EMPTY.copy(
        isConnected = true,
        meetingCountry = "Italy",
        sessionName = "Race",
        sessionKind = SessionKind.RACE,
        status = SessionStatus.STARTED,
        trackFlag = TrackFlag.GREEN,
        currentLap = 4,
        totalLaps = 53,
        drivers = listOf(
            driver(1, "RUS", "G. Russell"),
            driver(2, "GAS", "P. Gasly", gap = "+5.167"),
            driver(3, "NOR", "L. Norris", gap = "+7.212"),
        ),
    )

    @Test
    fun `the body line carries all three drivers`() {
        assertEquals("1 RUS · 2 GAS +5.167 · 3 NOR +7.212", LiveNotificationBuilder.text(race))
    }

    @Test
    fun `the expanded text keeps one driver per line for the final card`() {
        assertEquals(
            listOf("1  G. Russell", "2  P. Gasly  +5.167", "3  L. Norris  +7.212"),
            LiveNotificationBuilder.expandedText(race).lines(),
        )
    }

    @Test
    fun `the accent is the leader's team colour, F1 red when unknown`() {
        val papaya = race.copy(
            drivers = listOf(
                race.drivers[2].copy(position = 1, teamColorHex = "F47600"),
                race.drivers[0].copy(position = 2, teamColorHex = "00D2BE"),
            ),
        )
        assertEquals(0xFFF47600.toInt(), LiveNotificationBuilder.accentColor(papaya))
        assertEquals(0xFFF47600.toInt(), LiveNotificationBuilder.build(papaya, "").accentColor)
        assertEquals(LiveNotificationBuilder.COLOR_FALLBACK, LiveNotificationBuilder.accentColor(race))
        assertEquals(LiveNotificationBuilder.COLOR_FALLBACK, LiveNotificationBuilder.accentColor(LiveSessionState.EMPTY))
    }

    @Test
    fun `the chip shows the lap and the leader`() {
        assertEquals("L4 · RUS", LiveNotificationBuilder.shortCriticalText(race))
        assertEquals("L53 · RUS", LiveNotificationBuilder.shortCriticalText(race.copy(currentLap = 53)))
    }

    @Test
    fun `a session without laps falls back to the leading position`() {
        val qualifying = race.copy(
            sessionName = "Qualifying",
            sessionKind = SessionKind.QUALIFYING,
            currentLap = null,
            totalLaps = null,
        )
        assertEquals("P1 · RUS", LiveNotificationBuilder.shortCriticalText(qualifying))
    }

    @Test
    fun `the race progress bar runs over the laps`() {
        val progress = LiveNotificationBuilder.progress(race)
        assertTrue(progress is LiveNotificationBuilder.Progress.Segmented)
        progress as LiveNotificationBuilder.Progress.Segmented
        assertEquals(53, progress.max)
        assertEquals(4, progress.current)
        assertEquals(1, progress.segments.size)
    }

    @Test
    fun `an unknown race distance leaves the bar indeterminate`() {
        assertEquals(
            LiveNotificationBuilder.Progress.Indeterminate,
            LiveNotificationBuilder.progress(race.copy(totalLaps = null)),
        )
    }

    @Test
    fun `the title carries the lap count and, when it matters, the flag`() {
        assertEquals("Italy · Race · Lap 4/53", LiveNotificationBuilder.title(race))
        assertEquals(
            "Italy · Race · Lap 4/53 · RED FLAG",
            LiveNotificationBuilder.title(race.copy(trackFlag = TrackFlag.RED)),
        )
        // Before the race distance is known the lap still shows on its own.
        assertEquals("Italy · Race · Lap 4", LiveNotificationBuilder.title(race.copy(totalLaps = null)))
        // Qualifying has no lap target at all.
        assertEquals(
            "Italy · Qualifying",
            LiveNotificationBuilder.title(
                race.copy(
                    sessionName = "Qualifying",
                    sessionKind = SessionKind.QUALIFYING,
                    currentLap = null,
                    totalLaps = null,
                ),
            ),
        )
    }

    @Test
    fun `a finished session says FINISHED in the flag's place`() {
        val finished = race.copy(status = SessionStatus.FINISHED, trackFlag = TrackFlag.SC, currentLap = 53)
        assertEquals("Italy · Race · Lap 53/53 · FINISHED", LiveNotificationBuilder.title(finished))
        assertTrue(LiveNotificationBuilder.isFinished(race.copy(status = SessionStatus.FINALISED)))
        assertTrue(!LiveNotificationBuilder.isFinished(race))
    }

    @Test
    fun `qualifying is only finished after Q3`() {
        val q = race.copy(sessionKind = SessionKind.QUALIFYING, status = SessionStatus.FINISHED)
        assertTrue(!LiveNotificationBuilder.isFinished(q.copy(sessionPart = 1)))
        assertTrue(!LiveNotificationBuilder.isFinished(q.copy(sessionPart = 2)))
        assertTrue(LiveNotificationBuilder.isFinished(q.copy(sessionPart = 3)))
    }

    @Test
    fun `the fastest lap holder gets a stopwatch in front of the name`() {
        val withFastestLap = race.copy(
            drivers = listOf(
                driver(1, "RUS", "G. Russell"),
                driver(2, "GAS", "P. Gasly", gap = "+5.167", fastestLap = true),
                driver(3, "NOR", "L. Norris", gap = "+7.212"),
            ),
        )
        val icon = LiveNotificationBuilder.FASTEST_LAP_ICON
        assertEquals(
            "1 RUS · 2 $icon GAS +5.167 · 3 NOR +7.212",
            LiveNotificationBuilder.text(withFastestLap),
        )
        assertEquals(
            "2  $icon P. Gasly  +5.167",
            LiveNotificationBuilder.expandedText(withFastestLap).lines()[1],
        )
        // Nobody in the top three holds it: the line is untouched.
        assertEquals("1 RUS · 2 GAS +5.167 · 3 NOR +7.212", LiveNotificationBuilder.text(race))
    }

    @Test
    fun `build carries both layouts and the chip`() {
        val content = LiveNotificationBuilder.build(race, "Connecting")
        assertEquals("1 RUS · 2 GAS +5.167 · 3 NOR +7.212", content.text)
        assertEquals(3, content.expandedText.lines().size)
        assertEquals("L4 · RUS", content.shortCriticalText)
    }

    // ------------------------------------------------------------ MetricStyle (Android 17)

    private val metricRace = race.copy(
        drivers = listOf(
            driver(1, "VER", "M. Verstappen"),
            driver(2, "NOR", "L. Norris", gap = "+1.234", interval = "+1.234"),
            driver(3, "LEC", "C. Leclerc", gap = "+1.690", interval = "+0.456"),
        ),
        currentLap = 34,
        totalLaps = 57,
    )

    private val qualifying = race.copy(
        sessionName = "Qualifying",
        sessionKind = SessionKind.QUALIFYING,
        currentLap = null,
        totalLaps = null,
        drivers = listOf(
            driver(1, "RUS", "G. Russell", best = "1:18.792"),
            driver(2, "NOR", "L. Norris", best = "1:18.915"),
            driver(3, "LEC", "C. Leclerc", best = "1:19.092"),
        ),
    )

    private fun spec(
        value: String,
        label: String,
        semantic: LiveNotificationBuilder.MetricSemantic = LiveNotificationBuilder.MetricSemantic.UNSPECIFIED,
    ) = LiveNotificationBuilder.MetricSpec(value, label, semantic)

    @Test
    fun `race metrics are the leader, the gap to P2 and P3's interval`() {
        assertEquals(
            listOf(
                spec("VER", "Lap 34/57"),
                spec("+1.234", "P2 NOR"),
                spec("+0.456", "P3 LEC", LiveNotificationBuilder.MetricSemantic.INFO),
            ),
            LiveNotificationBuilder.metrics(metricRace),
        )
    }

    @Test
    fun `a missing interval is derived from the two gaps to the leader`() {
        val noInterval = metricRace.copy(
            drivers = metricRace.drivers.map { it.copy(interval = "") },
        )
        val metrics = LiveNotificationBuilder.metrics(noInterval)
        assertEquals("+1.234", metrics[1].value)
        assertEquals("+0.456", metrics[2].value)
    }

    @Test
    fun `a lapped car keeps the feed's text and a missing gap shows a placeholder`() {
        val lapped = metricRace.copy(
            drivers = listOf(
                driver(1, "VER", "M. Verstappen"),
                driver(2, "NOR", "L. Norris"),
                driver(3, "LEC", "C. Leclerc", gap = "1 LAP", interval = "1 LAP"),
            ),
        )
        val metrics = LiveNotificationBuilder.metrics(lapped)
        assertEquals(LiveNotificationBuilder.METRIC_MISSING, metrics[1].value)
        assertEquals("1 LAP", metrics[2].value)
        assertEquals(LiveNotificationBuilder.MetricSemantic.UNSPECIFIED, metrics[2].semantic)
    }

    @Test
    fun `flags colour the leader cell and neutralise the battle highlight`() {
        val sc = LiveNotificationBuilder.metrics(metricRace.copy(trackFlag = TrackFlag.SC))
        assertEquals(LiveNotificationBuilder.MetricSemantic.CAUTION, sc[0].semantic)
        assertEquals(LiveNotificationBuilder.MetricSemantic.UNSPECIFIED, sc[2].semantic)
        val red = LiveNotificationBuilder.metrics(metricRace.copy(trackFlag = TrackFlag.RED))
        assertEquals(LiveNotificationBuilder.MetricSemantic.DANGER, red[0].semantic)
        assertEquals(
            LiveNotificationBuilder.MetricSemantic.CAUTION,
            LiveNotificationBuilder.flagSemantic(TrackFlag.VSC),
        )
    }

    @Test
    fun `the leader's best lap labels the leader cell, with the stopwatch`() {
        val withBest = metricRace.copy(
            drivers = metricRace.drivers.mapIndexed { i, d -> if (i == 0) d.copy(bestLapTime = "1:21.046") else d },
        )
        assertEquals(spec("VER", "⏱︎ 1:21.046"), LiveNotificationBuilder.metrics(withBest)[0])
    }

    @Test
    fun `an unknown race distance labels the leader cell plainly`() {
        val metrics = LiveNotificationBuilder.metrics(metricRace.copy(currentLap = null, totalLaps = null))
        assertEquals(spec("VER", "Leader"), metrics[0])
    }

    @Test
    fun `qualifying metrics are the pole time and the gaps to it`() {
        assertEquals(
            listOf(
                spec("1:18.792", "P1 RUS"),
                spec("+0.123", "P2 NOR"),
                spec("+0.300", "P3 LEC"),
            ),
            LiveNotificationBuilder.metrics(qualifying),
        )
    }

    @Test
    fun `practice without lap times still has three cells`() {
        val practice = qualifying.copy(
            sessionKind = SessionKind.PRACTICE1,
            sessionName = "Practice 1",
            drivers = qualifying.drivers.map { it.copy(bestLapTime = "") },
        )
        val metrics = LiveNotificationBuilder.metrics(practice)
        assertEquals(3, metrics.size)
        assertTrue(metrics.all { it.value == LiveNotificationBuilder.METRIC_MISSING })
    }

    @Test
    fun `the gap to P2 is the critical metric`() {
        assertEquals(1, LiveNotificationBuilder.criticalMetric(LiveNotificationBuilder.metrics(metricRace)))
        val alone = metricRace.copy(drivers = metricRace.drivers.take(1))
        assertEquals(0, LiveNotificationBuilder.criticalMetric(LiveNotificationBuilder.metrics(alone)))
        assertEquals(
            LiveNotificationBuilder.NO_CRITICAL_METRIC,
            LiveNotificationBuilder.criticalMetric(emptyList()),
        )
    }

    @Test
    fun `build carries the metrics and they change the fingerprint`() {
        val content = LiveNotificationBuilder.build(metricRace, "Connecting")
        assertEquals(3, content.metrics.size)
        assertEquals(1, content.criticalMetric)
        // P3's interval is not in the one-line body, yet a change of it must be re-posted.
        val closer = metricRace.copy(
            drivers = metricRace.drivers.mapIndexed { index, d ->
                if (index == 2) d.copy(interval = "+0.301") else d
            },
        )
        val other = LiveNotificationBuilder.build(closer, "Connecting")
        assertEquals(content.text, other.text)
        assertTrue(content.summary != other.summary)
    }

    @Test
    fun `nothing to measure yet means no metrics`() {
        val content = LiveNotificationBuilder.build(LiveSessionState.EMPTY, "Connecting")
        assertTrue(content.metrics.isEmpty())
        assertEquals(LiveNotificationBuilder.NO_CRITICAL_METRIC, content.criticalMetric)
    }
}
