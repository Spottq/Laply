package com.flexy.f1live.update

/**
 * Version-string comparison for the update check: the installed `versionName` against a GitHub
 * release tag. Pure, so it is unit-tested without Android.
 *
 * Follows Semantic Versioning 2.0 precedence, loosened for how tags are written in practice:
 * - a leading `v`/`V` is ignored (`v1.0` == `1.0`);
 * - the numeric core may have any number of parts, missing ones count as 0 (`1.0` == `1.0.0`);
 * - build metadata after `+` is ignored;
 * - a pre-release after `-` sorts before its release (`1.1-beta` < `1.1`), and pre-releases
 *   compare identifier by identifier: numbers numerically, numbers before words, words by ASCII,
 *   and a shorter list before a longer one (`beta.2` < `beta.11` < `rc.1`).
 */
object AppVersion {

    /** Negative if [a] is older than [b], 0 if they are the same version, positive if newer. */
    fun compare(a: String, b: String): Int {
        val left = parse(a)
        val right = parse(b)
        val size = maxOf(left.core.size, right.core.size)
        for (i in 0 until size) {
            val diff = left.core.getOrElse(i) { 0L }.compareTo(right.core.getOrElse(i) { 0L })
            if (diff != 0) return diff
        }
        return comparePreRelease(left.preRelease, right.preRelease)
    }

    /** True when [candidate] (a release tag) is a strictly newer version than [installed]. */
    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    /** The tag without its `v` prefix, for display ("v1.1" -> "1.1"). */
    fun display(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    private class Parsed(val core: List<Long>, val preRelease: List<String>)

    private fun parse(raw: String): Parsed {
        val text = display(raw).substringBefore('+')
        val coreText = text.substringBefore('-')
        val preText = if ('-' in text) text.substringAfter('-') else ""
        // "1.2.3" -> [1, 2, 3]; a non-numeric part ("1.x") reads its leading digits, or 0.
        val core = coreText.split('.').map { part ->
            part.takeWhile(Char::isDigit).toLongOrNull() ?: 0L
        }
        val pre = if (preText.isEmpty()) emptyList() else preText.split('.', '-')
        return Parsed(core, pre)
    }

    private fun comparePreRelease(a: List<String>, b: List<String>): Int {
        // No pre-release outranks any pre-release of the same core version.
        if (a.isEmpty() || b.isEmpty()) return b.size.compareTo(0) - a.size.compareTo(0)
        for (i in 0 until minOf(a.size, b.size)) {
            val diff = compareIdentifier(a[i], b[i])
            if (diff != 0) return diff
        }
        return a.size.compareTo(b.size)
    }

    private fun compareIdentifier(a: String, b: String): Int {
        val an = a.toLongOrNull()
        val bn = b.toLongOrNull()
        return when {
            an != null && bn != null -> an.compareTo(bn)
            an != null -> -1
            bn != null -> 1
            else -> a.lowercase().compareTo(b.lowercase())
        }
    }
}
