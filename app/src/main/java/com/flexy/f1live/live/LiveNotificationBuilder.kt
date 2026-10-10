package com.flexy.f1live.live

import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.SessionBreak
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.model.TrackFlag
import java.util.Locale

object LiveNotificationBuilder {

    private const val DOT = " · "

    const val FASTEST_LAP_ICON = "⏱️"


    const val COLOR_SECTOR_OVERALL = 0xFFB040FF.toInt()
    const val COLOR_SECTOR_PERSONAL = 0xFF2ECC71.toInt()
    const val COLOR_SECTOR_SET = 0xFFFFD54F.toInt()
    const val COLOR_SECTOR_PENDING = 0xFF5A5A5A.toInt()

    const val FINISHED_LABEL = "FINISHED"

    const val COLOR_FALLBACK = 0xFFE10600.toInt()

    sealed interface Progress {
        data object Indeterminate : Progress

        data class Segmented(
            val segments: List<Pair<Int, Int?>>,
            val current: Int,
        ) : Progress {
            val max: Int get() = segments.sumOf { it.first }
        }
    }

    enum class MetricSemantic { UNSPECIFIED, INFO, SAFE, CAUTION, DANGER }

    data class MetricSpec(
        val value: String,
        val label: String,
        val semantic: MetricSemantic = MetricSemantic.UNSPECIFIED,
    )

    data class Content(
        val title: String,
        val text: String,
        val shortCriticalText: String?,
        val accentColor: Int,
        val progress: Progress,
        val expandedText: String,
        val leader: DriverTiming?,
        val summary: String,
        val metrics: List<MetricSpec> = emptyList(),
        val criticalMetric: Int = NO_CRITICAL_METRIC,
    )

    const val MAX_METRICS = 3

    const val NO_CRITICAL_METRIC = -1

    const val METRIC_MISSING = "--"

    private const val BATTLE_GAP_MS = 1_000L

    // ---------------------------------------------------------------- lap times

    fun parseLapMillis(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split(':')
        if (parts.size > 3) return null
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

    fun formatGapMillis(millis: Long): String =
        String.format(Locale.US, "+%.3f", millis / 1000.0)

    // ---------------------------------------------------------------- pieces

    fun title(state: LiveSessionState): String {
        SessionBreak.delayText(state)?.takeIf { it.isNotBlank() }?.let { return it }
        val place = state.meetingCountry.ifBlank { state.meetingName }.ifBlank { state.circuitShortName }
        val session = state.sessionName.ifBlank { defaultSessionName(state.sessionKind) }
        val part = SessionBreak.partLabel(state)
        val betweenParts = SessionBreak.isBetweenParts(state)
        return listOfNotNull(
            place.takeIf { it.isNotBlank() },
            session.takeIf { it.isNotBlank() },
            if (betweenParts && part != null) "$part $FINISHED_LABEL" else part,
            lapLabel(state),
            when {
                isFinished(state) -> FINISHED_LABEL
                betweenParts -> null
                else -> flagLabel(state.trackFlag)
            },
        ).joinToString(DOT).ifBlank { "Laply" }
    }

    fun lapLabel(state: LiveSessionState): String? {
        if (!isRaceLike(state.sessionKind)) return null
        val lap = state.currentLap?.takeIf { it > 0 } ?: return null
        val total = state.totalLaps?.takeIf { it > 0 } ?: return "Lap $lap"
        return "Lap $lap/$total"
    }

    fun flagLabel(flag: TrackFlag): String? = when (flag) {
        TrackFlag.RED -> "RED FLAG"
        TrackFlag.YELLOW -> "YELLOW"
        TrackFlag.SC -> "SAFETY CAR"
        TrackFlag.VSC -> "VSC"
        TrackFlag.VSC_ENDING -> "VSC ENDING"
        TrackFlag.GREEN, TrackFlag.UNKNOWN -> null
    }

    fun text(state: LiveSessionState): String = entries(state).joinToString(DOT)

    fun expandedText(state: LiveSessionState): String =
        entries(state, longNames = true).joinToString("\n")

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

    private fun gapFor(driver: DriverTiming, leaderMillis: Long?, race: Boolean): String {
        val reported = driver.gapToLeader.trim()
        if (reported.isNotEmpty()) return reported
        if (race) return driver.interval.trim()
        val own = parseLapMillis(driver.bestLapTime) ?: return ""
        val leader = leaderMillis ?: return driver.bestLapTime
        val delta = own - leader
        return if (delta <= 0L) driver.bestLapTime else formatGapMillis(delta)
    }

    fun shortCriticalText(state: LiveSessionState): String? {
        val leader = state.drivers.firstOrNull() ?: return null
        val tla = leader.tla.ifBlank { leader.racingNumber }.trim()
        if (tla.isBlank()) return null
        val lap = state.currentLap?.takeIf { it > 0 && isRaceLike(state.sessionKind) }
        return (if (lap != null) "L$lap" else "P1") + DOT + tla
    }

    fun accentColor(state: LiveSessionState): Int =
        parseTeamColor(state.drivers.firstOrNull()?.teamColorHex) ?: COLOR_FALLBACK

    fun parseTeamColor(hex: String?): Int? {
        val cleaned = hex?.trim()?.removePrefix("#").orEmpty()
        if (cleaned.length != 6 && cleaned.length != 8) return null
        val value = cleaned.toLongOrNull(16) ?: return null
        return if (cleaned.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }

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

    fun sectorProgress(driver: DriverTiming): Progress.Segmented? {
        val sectors = driver.sectors.ifEmpty { driver.bestSectors }.take(3)
        if (sectors.isEmpty()) return null
        val millis = sectors.map { parseLapMillis(it.value) }
        val known = millis.filterNotNull()
        if (known.isEmpty()) return null
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
        var completed = 0
        for (m in millis) { if (m == null) break; completed++ }
        val current = lengths.take(completed).sum()
        return Progress.Segmented(segments = segments, current = current)
    }

    // ---------------------------------------------------------------- metrics (Android 17)

    fun metrics(state: LiveSessionState): List<MetricSpec> {
        val top = state.topThree
        if (top.isEmpty()) return emptyList()
        val race = isRaceLike(state.sessionKind)
        val flagSemantic = flagSemantic(state.trackFlag)
        val racing = flagSemantic == MetricSemantic.UNSPECIFIED
        val leader = top.first()
        val first = if (race) {
            MetricSpec(
                value = leader.tla.ifBlank { leader.racingNumber }.ifBlank { METRIC_MISSING },
                label = "P1",
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

    fun criticalMetric(metrics: List<MetricSpec>): Int = when {
        metrics.size > 1 -> 1
        metrics.isNotEmpty() -> 0
        else -> NO_CRITICAL_METRIC
    }

    fun flagSemantic(flag: TrackFlag): MetricSemantic = when (flag) {
        TrackFlag.RED -> MetricSemantic.DANGER
        TrackFlag.SC, TrackFlag.VSC, TrackFlag.VSC_ENDING, TrackFlag.YELLOW -> MetricSemantic.CAUTION
        TrackFlag.GREEN, TrackFlag.UNKNOWN -> MetricSemantic.UNSPECIFIED
    }

    private fun positionLabel(driver: DriverTiming, index: Int): String {
        val position = if (driver.position > 0) driver.position else index + 1
        val name = driver.tla.ifBlank { driver.racingNumber }
        return listOf("P$position", name).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun gapMillis(value: String): Long? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("+")) return null
        return parseLapMillis(trimmed.removePrefix("+"))
    }

    private fun intervalFromGaps(ahead: DriverTiming, behind: DriverTiming): String {
        val behindGap = gapMillis(behind.gapToLeader) ?: return behind.gapToLeader.trim()
        val aheadGap = gapMillis(ahead.gapToLeader) ?: return ""
        val delta = behindGap - aheadGap
        return if (delta >= 0L) formatGapMillis(delta) else ""
    }

    fun isFinished(state: LiveSessionState): Boolean = when (state.status) {
        SessionStatus.FINALISED, SessionStatus.ENDS -> true
        SessionStatus.FINISHED ->
            !isQualifyingLike(state.sessionKind) || (state.sessionPart ?: 3) !in 1..2
        else -> false
    }

    fun isSessionOver(status: SessionStatus): Boolean =
        status == SessionStatus.FINALISED || status == SessionStatus.ENDS

    // ---------------------------------------------------------------- assembly

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
