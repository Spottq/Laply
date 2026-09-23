package com.flexy.f1live.live

import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.model.TrackFlag
import java.util.Locale

/**
 * Pure (Android-free) formatting for the Live Update notification.
 *
 * Everything here is a plain function over [LiveSessionState] so it can be reasoned about and
 * unit-tested without a device. [LiveUpdateService] only translates the resulting [Content] into
 * platform calls.
 */
object LiveNotificationBuilder {

    /** U+00B7 MIDDLE DOT used as the separator everywhere in the notification. */
    private const val DOT = " · "

    /** Stopwatch shown in front of whoever holds the fastest lap of the session. */
    const val FASTEST_LAP_ICON = "⏱️"

    /**
     * Monochrome stopwatch for the MetricStyle best-lap label: U+23F1 with the text-presentation
     * selector (U+FE0E), so Android draws it from Noto Sans Symbols in the label's own colour
     * instead of the colour emoji. A metric label is plain text - no icon slot, and ImageSpans do
     * not survive into the system UI - so a glyph is the only way to get an icon there.
     */
    const val STOPWATCH_GLYPH = "⏱︎"

    /** F1 timing-tower colours: purple = overall fastest, green = personal best, yellow = set. */
    const val COLOR_SECTOR_OVERALL = 0xFFB040FF.toInt()
    const val COLOR_SECTOR_PERSONAL = 0xFF2ECC71.toInt()
    const val COLOR_SECTOR_SET = 0xFFFFD54F.toInt()
    const val COLOR_SECTOR_PENDING = 0xFF5A5A5A.toInt()

    /** Title suffix once the session is over (see [isFinished]); takes the flag's place. */
    const val FINISHED_LABEL = "FINISHED"

    /** F1 red: the Live Update accent, and the fallback wherever a team colour is unknown. */
    const val COLOR_FALLBACK = 0xFFE10600.toInt()

    /** Progress bar description, resolved into a `Notification.ProgressStyle` by the service. */
    sealed interface Progress {
        /** No meaningful progress known: spinner. */
        data object Indeterminate : Progress

        /**
         * [segments] are (length, colorOrNull) pairs; the bar's max is the sum of the lengths and
         * [current] is the position of the tracker on that scale.
         */
        data class Segmented(
            val segments: List<Pair<Int, Int?>>,
            val current: Int,
        ) : Progress {
            val max: Int get() = segments.sumOf { it.first }
        }
    }

    /**
     * Platform-free mirror of `Notification.SEMANTIC_STYLE_*` (API 37), so the choice of colour
     * stays testable here and only the service touches the framework constants.
     */
    enum class MetricSemantic { UNSPECIFIED, INFO, SAFE, CAUTION, DANGER }

    /**
     * One cell of an Android 17 `Notification.MetricStyle`: a [value] (always shown, also in the
     * collapsed row and on the AOD) above a short [label]. The service turns it into a
     * `Notification.Metric` with a `FixedText` value - gaps such as "1 LAP" are not numbers, and a
     * float would drop the leading "+" that makes "+0.412" read as a gap.
     */
    data class MetricSpec(
        val value: String,
        val label: String,
        val semantic: MetricSemantic = MetricSemantic.UNSPECIFIED,
    )

    /** Everything the service needs to render one notification. */
    data class Content(
        val title: String,
        val text: String,
        val shortCriticalText: String?,
        val accentColor: Int,
        val progress: Progress,
        /** Same top three, one per line: only the final BigTextStyle card can show it. */
        val expandedText: String,
        val leader: DriverTiming?,
        /** Compact fingerprint of all visible content; used to skip redundant notify() calls. */
        val summary: String,
        /**
         * Up to [MAX_METRICS] cells for the Android 17 MetricStyle rendering; empty while there
         * is nothing to measure yet (connecting, waiting for the start), in which case the service
         * keeps the ProgressStyle so the "Starts at 15:00" text stays visible - MetricStyle never
         * shows the content text.
         */
        val metrics: List<MetricSpec> = emptyList(),
        /** Index into [metrics] the system should treat as the critical one; -1 for none. */
        val criticalMetric: Int = NO_CRITICAL_METRIC,
    )

    /** `Notification.MetricStyle` renders at most three metrics, in equal-width columns. */
    const val MAX_METRICS = 3

    /** Mirrors `Notification.MetricStyle.METRIC_INDEX_NONE`. */
    const val NO_CRITICAL_METRIC = -1

    /** Placeholder for a metric whose value is not known yet; a metric must have a value. */
    const val METRIC_MISSING = "--"

    /** Gaps under a second are a fight for position (the old DRS window): highlighted as INFO. */
    private const val BATTLE_GAP_MS = 1_000L

    // ---------------------------------------------------------------- lap times

    /**
     * Parses an F1 lap/sector time into milliseconds.
     * Accepts "1:22.612", "22.612", "1:02:33.4" and tolerates blanks; returns null when unparseable.
     */
    fun parseLapMillis(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split(':')
        if (parts.size > 3) return null
        // Accumulate in seconds, shifting left by 60 for each hour/minute group.
        var seconds = 0.0
        for ((index, part) in parts.withIndex()) {
            val value = if (index == parts.lastIndex) {
                part.toDoubleOrNull() ?: return null
            } else {
                (part.toLongOrNull() ?: return null).toDouble()
            }
            seconds = seconds * 60.0 + value
        }
        val millis = Math.round(seconds * 1000.0)
        return if (millis >= 0L) millis else null
    }

    /** 19 -> "+0.019", 1_234 -> "+1.234". */
    fun formatGapMillis(millis: Long): String =
        String.format(Locale.US, "+%.3f", millis / 1000.0)

    // ---------------------------------------------------------------- pieces

    /** "Italy · Race · Lap 4/53" - or "Italy · Qualifying · Q3" outside a race. */
    fun title(state: LiveSessionState): String {
        val place = state.meetingCountry.ifBlank { state.meetingName }.ifBlank { state.circuitShortName }
        val session = state.sessionName.ifBlank { defaultSessionName(state.sessionKind) }
        val part = state.sessionPart
            ?.takeIf { it in 1..3 && isQualifyingLike(state.sessionKind) }
            ?.let { "Q$it" }
        return listOfNotNull(
            place.takeIf { it.isNotBlank() },
            session.takeIf { it.isNotBlank() },
            part,
            lapLabel(state),
            // Once the chequered flag is out the track flag is history: the slot says FINISHED.
            if (isFinished(state)) FINISHED_LABEL else flagLabel(state.trackFlag),
        ).joinToString(DOT).ifBlank { "Laply" }
    }

    /**
     * "Lap 4/53" while a race runs, "Lap 4" until the race distance is known, null everywhere else.
     * The title has room to spell it out; the status-bar chip keeps the terse "L4".
     */
    fun lapLabel(state: LiveSessionState): String? {
        if (!isRaceLike(state.sessionKind)) return null
        val lap = state.currentLap?.takeIf { it > 0 } ?: return null
        val total = state.totalLaps?.takeIf { it > 0 } ?: return "Lap $lap"
        return "Lap $lap/$total"
    }

    /** Only the flags that change what the viewer is looking at; green and unknown say nothing. */
    fun flagLabel(flag: TrackFlag): String? = when (flag) {
        TrackFlag.RED -> "RED FLAG"
        TrackFlag.YELLOW -> "YELLOW"
        TrackFlag.SC -> "SAFETY CAR"
        TrackFlag.VSC -> "VSC"
        TrackFlag.VSC_ENDING -> "VSC ENDING"
        TrackFlag.GREEN, TrackFlag.UNKNOWN -> null
    }

    /**
     * The top three on **one** line. `Notification.ProgressStyle` renders a single line of body
     * text, so the previous one-driver-per-line layout showed nothing but the leader:
     * ```
     * 1 ⏱️ RUS · 2 GAS +5.167 · 3 NOR +7.212
     * ```
     * Three-letter codes rather than "P. Gasly" so all three fit before the line is ellipsised.
     */
    fun text(state: LiveSessionState): String = entries(state).joinToString(DOT)

    /**
     * The same three, one per line. Only `Notification.BigTextStyle` can show that, which in this
     * app is the "session finished" card.
     */
    fun expandedText(state: LiveSessionState): String =
        entries(state, longNames = true).joinToString("\n")

    /** One "position name detail" string per driver of the top three. */
    private fun entries(state: LiveSessionState, longNames: Boolean = false): List<String> {
        val top = state.topThree
        if (top.isEmpty()) return emptyList()
        val leaderMillis = parseLapMillis(top.first().bestLapTime)
        val race = isRaceLike(state.sessionKind)
        val separator = if (longNames) "  " else " "
        return top.mapIndexed { index, driver ->
            val position = if (driver.position > 0) driver.position else index + 1
            val plain = if (longNames) {
                driver.shortName.ifBlank { driver.tla.ifBlank { driver.racingNumber } }
            } else {
                driver.tla.ifBlank { driver.shortName.ifBlank { driver.racingNumber } }
            }
            // The purple lap of the timing tower has no colour to spend here, so it gets a glyph.
            val name = if (driver.fastestLap) "$FASTEST_LAP_ICON $plain" else plain
            val detail = if (index == 0) leaderDetail(driver, race) else gapFor(driver, leaderMillis, race)
            listOf(position.toString(), name, detail)
                .filter { it.isNotBlank() }
                .joinToString(separator)
        }
    }

    private fun leaderDetail(leader: DriverTiming, race: Boolean): String = when {
        leader.bestLapTime.isNotBlank() -> leader.bestLapTime
        race && leader.lastLapTime.isNotBlank() -> leader.lastLapTime
        else -> ""
    }

    /**
     * In races the feed's gap is authoritative ("+0.019", "1 LAP"). In qualifying/practice it is
     * often empty, so the gap is derived from the difference of the best laps.
     */
    private fun gapFor(driver: DriverTiming, leaderMillis: Long?, race: Boolean): String {
        val reported = driver.gapToLeader.trim()
        if (reported.isNotEmpty()) return reported
        if (race) return driver.interval.trim()
        val own = parseLapMillis(driver.bestLapTime) ?: return ""
        val leader = leaderMillis ?: return driver.bestLapTime
        val delta = own - leader
        return if (delta <= 0L) driver.bestLapTime else formatGapMillis(delta)
    }

    /**
     * The status-bar chip text: the lap the race is on, then the leader - "L4 · RUS", using the same
     * separator as the rest of the notification. Sessions with no lap count (practice, qualifying)
     * fall back to "P1 · RUS". The chip is 96 dp wide, so this is kept as terse as it can be.
     */
    fun shortCriticalText(state: LiveSessionState): String? {
        val leader = state.drivers.firstOrNull() ?: return null
        val tla = leader.tla.ifBlank { leader.racingNumber }.trim()
        if (tla.isBlank()) return null
        val lap = state.currentLap?.takeIf { it > 0 && isRaceLike(state.sessionKind) }
        return (if (lap != null) "L$lap" else "P1") + DOT + tla
    }

    /**
     * The notification's accent is always F1 red: the brand, and the same whoever leads. The
     * leader's team colour still lives on in the tracker avatar (see [AvatarIcons]).
     */
    @Suppress("UNUSED_PARAMETER")
    fun accentColor(state: LiveSessionState): Int = COLOR_FALLBACK

    /** "F47600" / "#F47600" -> 0xFFF47600; null on anything unexpected. */
    fun parseTeamColor(hex: String?): Int? {
        val cleaned = hex?.trim()?.removePrefix("#").orEmpty()
        if (cleaned.length != 6 && cleaned.length != 8) return null
        val value = cleaned.toLongOrNull(16) ?: return null
        return if (cleaned.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }

    /**
     * A race is a plain lap counter: one segment the length of the race, with the tracker on the
     * lap the leader is on. Practice and qualifying have no lap target and keep the sector bar.
     */
    fun progress(state: LiveSessionState): Progress {
        if (isRaceLike(state.sessionKind)) {
            val total = state.totalLaps ?: 0
            val current = state.currentLap ?: 0
            if (total > 0) {
                return Progress.Segmented(
                    segments = listOf(total to null),
                    current = current.coerceIn(0, total),
                )
            }
            return Progress.Indeterminate
        }
        val leader = state.drivers.firstOrNull() ?: return Progress.Indeterminate
        return sectorProgress(leader) ?: Progress.Indeterminate
    }

    /** Builds the sector bar for one driver; null when the driver has no sector data at all. */
    fun sectorProgress(driver: DriverTiming): Progress.Segmented? {
        // A finished session has no current lap; its best sectors are the only ones left.
        val sectors = driver.sectors.ifEmpty { driver.bestSectors }.take(3)
        if (sectors.isEmpty()) return null
        val millis = sectors.map { parseLapMillis(it.value) }
        val known = millis.filterNotNull()
        if (known.isEmpty()) return null
        // Sector times are ~20-45 s; work in tenths so segment lengths stay small ints.
        val fallback = (known.sum() / known.size)
        val lengths = millis.map { ((it ?: fallback) / 100L).toInt().coerceAtLeast(1) }
        val segments = sectors.mapIndexed { index, sector ->
            val color = when {
                millis[index] == null -> COLOR_SECTOR_PENDING
                sector.overallFastest -> COLOR_SECTOR_OVERALL
                sector.personalFastest -> COLOR_SECTOR_PERSONAL
                else -> COLOR_SECTOR_SET
            }
            lengths[index] to color
        }
        // Completed sectors, counted from the start of the lap; a gap means the lap restarted.
        var completed = 0
        for (m in millis) { if (m == null) break; completed++ }
        val current = lengths.take(completed).sum()
        return Progress.Segmented(segments = segments, current = current)
    }

    // ---------------------------------------------------------------- metrics (Android 17)

    /**
     * The three cells of the MetricStyle Live Update. The title already names the session, the
     * lap and the flag, so the cells are spent on what ProgressStyle could only squeeze into one
     * ellipsised line - the gaps:
     *
     * Race / sprint - who leads, and the two gaps that decide the podium:
     * ```
     *   VER           +1.234       +0.456
     *   ⏱ 1:21.046    P2 NOR       P3 LEC
     * ```
     * P2's value is its gap to the leader, P3's is its *interval* to P2 - the battle behind, not a
     * second distance to the leader that says less.
     *
     * Qualifying / practice - the provisional pole time and the gaps to it:
     * ```
     *   1:18.792     +0.123       +0.300
     *   P1 RUS       P2 NOR       P3 LEC
     * ```
     * Fewer drivers give fewer cells; no drivers gives none (see [Content.metrics]).
     */
    fun metrics(state: LiveSessionState): List<MetricSpec> {
        val top = state.topThree
        if (top.isEmpty()) return emptyList()
        val race = isRaceLike(state.sessionKind)
        val flagSemantic = flagSemantic(state.trackFlag)
        // A neutralised track freezes the order: a gap under a second is no battle then.
        val racing = flagSemantic == MetricSemantic.UNSPECIFIED
        val leader = top.first()
        val first = if (race) {
            MetricSpec(
                value = leader.tla.ifBlank { leader.racingNumber }.ifBlank { METRIC_MISSING },
                label = leaderBestLapLabel(leader) ?: lapLabel(state) ?: "Leader",
                semantic = flagSemantic,
            )
        } else {
            MetricSpec(
                value = leader.bestLapTime.ifBlank { METRIC_MISSING },
                label = positionLabel(leader, 0),
                semantic = flagSemantic,
            )
        }
        val leaderMillis = parseLapMillis(leader.bestLapTime)
        val others = top.drop(1).mapIndexed { offset, driver ->
            val index = offset + 1
            val value = when {
                !race -> gapFor(driver, leaderMillis, race = false)
                // P2's gap to the leader is its interval as well; the feed fills one or the other.
                index == 1 -> driver.gapToLeader.trim().ifBlank { driver.interval.trim() }
                else -> driver.interval.trim().ifBlank { intervalFromGaps(top[index - 1], driver) }
            }.ifBlank { METRIC_MISSING }
            val battle = race && racing && (gapMillis(value)?.let { it < BATTLE_GAP_MS } ?: false)
            MetricSpec(
                value = value,
                label = positionLabel(driver, index),
                semantic = if (battle) MetricSemantic.INFO else MetricSemantic.UNSPECIFIED,
            )
        }
        return (listOf(first) + others).take(MAX_METRICS)
    }

    /**
     * The gap P1 -> P2 is what a glance at the notification is for, in every kind of session; the
     * leader cell only when nobody else has a time yet.
     */
    fun criticalMetric(metrics: List<MetricSpec>): Int = when {
        metrics.size > 1 -> 1
        metrics.isNotEmpty() -> 0
        else -> NO_CRITICAL_METRIC
    }

    /** Track status as a colour: stopped is danger, neutralised or yellow is caution. */
    fun flagSemantic(flag: TrackFlag): MetricSemantic = when (flag) {
        TrackFlag.RED -> MetricSemantic.DANGER
        TrackFlag.SC, TrackFlag.VSC, TrackFlag.VSC_ENDING, TrackFlag.YELLOW -> MetricSemantic.CAUTION
        TrackFlag.GREEN, TrackFlag.UNKNOWN -> MetricSemantic.UNSPECIFIED
    }

    /**
     * "⏱ 1:21.046": the leader's own best lap, over the leader's name. The lap count already sits
     * in the title, so the cell is free for it; without a time yet it falls back to the lap.
     */
    private fun leaderBestLapLabel(leader: DriverTiming): String? =
        leader.bestLapTime.trim().takeIf { it.isNotEmpty() }?.let { "$STOPWATCH_GLYPH $it" }

    /** "P2 NOR"; the list index stands in for a position the feed has not sent yet. */
    private fun positionLabel(driver: DriverTiming, index: Int): String {
        val position = if (driver.position > 0) driver.position else index + 1
        val name = driver.tla.ifBlank { driver.racingNumber }
        return listOf("P$position", name).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** "+0.412" -> 412; null for "1 LAP", blanks and anything else that is not a time. */
    fun gapMillis(value: String): Long? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("+")) return null
        return parseLapMillis(trimmed.removePrefix("+"))
    }

    /** Interval between two cars from their gaps to the leader, when the feed sent no interval. */
    private fun intervalFromGaps(ahead: DriverTiming, behind: DriverTiming): String {
        val behindGap = gapMillis(behind.gapToLeader) ?: return behind.gapToLeader.trim()
        val aheadGap = gapMillis(ahead.gapToLeader) ?: return ""
        val delta = behindGap - aheadGap
        return if (delta >= 0L) formatGapMillis(delta) else ""
    }

    /**
     * The chequered flag is out. FINISHED alone is not enough in qualifying: the feed reports it
     * after Q1 and Q2 too, and the session goes on a few minutes later - only Q3's (or an unknown
     * part's) FINISHED ends it.
     */
    fun isFinished(state: LiveSessionState): Boolean = when (state.status) {
        SessionStatus.FINALISED, SessionStatus.ENDS -> true
        SessionStatus.FINISHED ->
            !isQualifyingLike(state.sessionKind) || (state.sessionPart ?: 3) !in 1..2
        else -> false
    }

    /** True once the session is over for good and the service should retire itself. */
    fun isSessionOver(status: SessionStatus): Boolean =
        status == SessionStatus.FINALISED || status == SessionStatus.ENDS

    // ---------------------------------------------------------------- assembly

    /** Builds the full [Content]; [connectingText] is used while the feed has nothing yet. */
    fun build(state: LiveSessionState, connectingText: String): Content {
        val body = text(state)
        val expanded = expandedText(state)
        val hasData = state.drivers.isNotEmpty()
        val title = if (hasData || state.sessionName.isNotBlank()) title(state) else "Laply"
        val text = if (body.isNotBlank()) body else connectingText
        val shortCritical = if (hasData) shortCriticalText(state) else null
        val progress = if (hasData) progress(state) else Progress.Indeterminate
        val leader = state.drivers.firstOrNull()
        val color = accentColor(state)
        val progressKey = when (progress) {
            is Progress.Indeterminate -> "ind"
            is Progress.Segmented ->
                "${progress.current}/${progress.max}/" +
                    progress.segments.joinToString(",") { (len, color) -> "$len:${color ?: 0}" }
        }
        val metrics = if (hasData) metrics(state) else emptyList()
        val critical = criticalMetric(metrics)
        // A gap can change while the one-line body stays identical (e.g. P3's interval is not in
        // it), so the metrics are part of the fingerprint or MetricStyle would go stale.
        val metricsKey = metrics.joinToString(",") { "${it.value}/${it.label}/${it.semantic.ordinal}" } +
            "#$critical"
        val summary = listOf(
            title,
            text,
            shortCritical.orEmpty(),
            progressKey,
            color.toString(),
            leader?.tla.orEmpty(),
            state.status.name,
            state.trackFlag.name,
            metricsKey,
        ).joinToString("|")
        return Content(
            title = title,
            text = text,
            shortCriticalText = shortCritical,
            accentColor = color,
            progress = progress,
            expandedText = expanded.ifBlank { text },
            leader = leader,
            summary = summary,
            metrics = metrics,
            criticalMetric = critical,
        )
    }

    // ---------------------------------------------------------------- helpers

    fun isRaceLike(kind: SessionKind): Boolean =
        kind == SessionKind.RACE || kind == SessionKind.SPRINT

    fun isQualifyingLike(kind: SessionKind): Boolean =
        kind == SessionKind.QUALIFYING || kind == SessionKind.SPRINT_QUALIFYING

    private fun defaultSessionName(kind: SessionKind): String = when (kind) {
        SessionKind.PRACTICE1 -> "Practice 1"
        SessionKind.PRACTICE2 -> "Practice 2"
        SessionKind.PRACTICE3 -> "Practice 3"
        SessionKind.SPRINT_QUALIFYING -> "Sprint Qualifying"
        SessionKind.SPRINT -> "Sprint"
        SessionKind.QUALIFYING -> "Qualifying"
        SessionKind.RACE -> "Race"
        SessionKind.UNKNOWN -> ""
    }
}
