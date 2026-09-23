package com.flexy.f1live.live

import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.model.TrackFlag
import com.flexy.f1live.ui.SampleData
import java.util.Locale

/**
 * A scripted race for the "Test Live Update" button in Settings: the real notification pipeline
 * fed with fake timing, so the Live Update (and the Android 17 MetricStyle) can be looked at
 * between sessions. The gap P1 -> P2 closes into DRS range, then a Safety Car bunches the field,
 * and the last frames are the chequered flag.
 */
object LiveDemo {

    const val FRAME_COUNT = 27
    const val FRAME_INTERVAL_MS = 2_500L

    private const val TOTAL_LAPS = 53
    private const val FIRST_LAP = 30

    /** Frames that run behind the Safety Car. */
    private val SAFETY_CAR = 16..19

    /** Frames after the chequered flag. */
    private const val FIRST_FINISHED = 24

    fun frame(index: Int): LiveSessionState {
        val step = index.coerceIn(0, FRAME_COUNT - 1)
        val finished = step >= FIRST_FINISHED
        val underSc = step in SAFETY_CAR
        val afterSc = step > SAFETY_CAR.last
        // P2 closes from 2.4 s at 0.13 s a frame; the Safety Car squeezes everything to ~0.4 s.
        val gapP2 = when {
            underSc || afterSc -> 0.412 + (step - SAFETY_CAR.first) * 0.05
            else -> (2.4 - step * 0.13).coerceAtLeast(0.35)
        }
        val intervalP3 = when {
            underSc || afterSc -> 0.389
            else -> 1.9 + step * 0.04
        }
        val order = listOf("VER", "NOR", "GAS", "ANT", "RUS", "HAM", "COL", "LIN")
        val byTla = SampleData.drivers.associateBy { it.tla }
        var gapToLeader = 0.0
        val drivers = order.mapIndexedNotNull { i, tla ->
            val base = byTla[tla] ?: return@mapIndexedNotNull null
            val interval = when (i) {
                0 -> 0.0
                1 -> gapP2
                2 -> intervalP3
                else -> if (underSc || afterSc) 0.5 else 1.1 + i * 0.3
            }
            gapToLeader += interval
            base.copy(
                position = i + 1,
                gapToLeader = if (i == 0) "" else "+%.3f".format(Locale.US, gapToLeader),
                interval = if (i == 0) "" else "+%.3f".format(Locale.US, interval),
                inPit = false,
                knockedOut = false,
                fastestLap = i == 1,
                // The leader finds a little time every few laps, so the best-lap cell moves too.
                bestLapTime = if (i == 0) "1:21.%03d".format(Locale.US, 412 - (step / 4) * 37) else base.bestLapTime,
            )
        }
        return SampleData.liveState.copy(
            sessionName = "Race",
            sessionKind = SessionKind.RACE,
            sessionPart = null,
            status = if (finished) SessionStatus.FINISHED else SessionStatus.STARTED,
            trackFlag = if (underSc) TrackFlag.SC else TrackFlag.GREEN,
            currentLap = if (finished) TOTAL_LAPS else (FIRST_LAP + step / 2).coerceAtMost(TOTAL_LAPS),
            totalLaps = TOTAL_LAPS,
            drivers = drivers,
            lastUpdateUtcMillis = System.currentTimeMillis(),
        )
    }
}
