package com.flexy.f1live.widget.lock

import com.flexy.f1live.widget.standings.TablePlan
import org.junit.Assert.assertEquals
import org.junit.Test

class StandingsLockPlanTest {

    private val slot = LockWidgetLargePlan.DEFAULT_HEIGHT_DP

    @Test
    fun `the 2x2 slot holds five rows and the title line`() {
        assertEquals(TablePlan(rows = 5, lineAfter = 3), StandingsLockPlan.table(slot, 1f, entries = 20, contenders = 3))
        assertEquals(TablePlan(rows = 5, lineAfter = 1), StandingsLockPlan.table(slot, 1f, entries = 10, contenders = 1))
    }

    @Test
    fun `no line while everyone shown can still win`() {
        assertEquals(TablePlan(rows = 5, lineAfter = null), StandingsLockPlan.table(slot, 1f, entries = 20, contenders = 20))
        assertEquals(TablePlan(rows = 5, lineAfter = null), StandingsLockPlan.table(slot, 1f, entries = 20, contenders = null))
    }

    @Test
    fun `a shorter slot or a larger font gives up rows before the line`() {
        assertEquals(TablePlan(rows = 4, lineAfter = 3), StandingsLockPlan.table(124, 1f, entries = 20, contenders = 3))
        assertEquals(TablePlan(rows = 3, lineAfter = null), StandingsLockPlan.table(slot, 1.3f, entries = 20, contenders = 3))
    }

    @Test
    fun `an empty table has no rows`() {
        assertEquals(TablePlan(rows = 0, lineAfter = null), StandingsLockPlan.table(slot, 1f, entries = 0, contenders = null))
    }

    @Test
    fun `the title fight drops its small lines as the font grows`() {
        assertEquals(2, StandingsLockPlan.leaderLines(slot, 1f))
        assertEquals(2, StandingsLockPlan.leaderLines(124, 1f))
        assertEquals(1, StandingsLockPlan.leaderLines(slot, 1.3f))
        assertEquals(0, StandingsLockPlan.leaderLines(slot, 1.7f))
    }
}
