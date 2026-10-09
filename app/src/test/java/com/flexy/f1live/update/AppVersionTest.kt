package com.flexy.f1live.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    private fun assertOrder(vararg ascending: String) {
        for (i in 0 until ascending.lastIndex) {
            val older = ascending[i]
            val newer = ascending[i + 1]
            assertTrue("$older < $newer", AppVersion.compare(older, newer) < 0)
            assertTrue("$newer > $older", AppVersion.compare(newer, older) > 0)
        }
    }

    @Test
    fun equalVersionsIgnoreTagPrefixAndTrailingZeros() {
        assertEquals(0, AppVersion.compare("v1.0", "1.0"))
        assertEquals(0, AppVersion.compare("1.0", "1.0.0"))
        assertEquals(0, AppVersion.compare("V2", "2.0.0"))
        assertEquals(0, AppVersion.compare(" v1.2.3 ", "1.2.3"))
        assertEquals(0, AppVersion.compare("1.0+build.7", "1.0"))
    }

    @Test
    fun numericPartsCompareNumericallyNotLexically() {
        assertOrder("1.0", "1.0.1", "1.1", "1.2", "1.10", "2.0", "10.0")
        assertOrder("1.9.9", "1.10.0")
    }

    @Test
    fun preReleaseSortsBeforeItsRelease() {
        assertOrder("1.1-alpha", "1.1-alpha.1", "1.1-alpha.beta", "1.1-beta", "1.1-beta.2", "1.1-beta.11", "1.1-rc.1", "1.1")
        assertOrder("1.0", "1.1-beta")
        assertEquals(0, AppVersion.compare("v1.1-RC1", "1.1-rc1"))
    }

    @Test
    fun isNewerOnlyForStrictlyHigherVersions() {
        assertTrue(AppVersion.isNewer("v1.1", "1.0"))
        assertTrue(AppVersion.isNewer("v1.0.1", "1.0"))
        assertFalse(AppVersion.isNewer("v1.0", "1.0"))
        assertFalse(AppVersion.isNewer("v1.0.0", "1.0"))
        assertFalse(AppVersion.isNewer("v0.9", "1.0"))
        assertFalse(AppVersion.isNewer("v1.1-beta", "1.1"))
    }

    @Test
    fun malformedPartsDoNotThrow() {
        assertEquals(0, AppVersion.compare("", "0"))
        assertTrue(AppVersion.isNewer("v1.x", "0.9"))
        assertEquals("1.1", AppVersion.display("v1.1"))
    }
}
