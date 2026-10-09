package com.flexy.f1live.update

object AppVersion {

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

    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    fun display(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    private class Parsed(val core: List<Long>, val preRelease: List<String>)

    private fun parse(raw: String): Parsed {
        val text = display(raw).substringBefore('+')
        val coreText = text.substringBefore('-')
        val preText = if ('-' in text) text.substringAfter('-') else ""
        val core = coreText.split('.').map { part ->
            part.takeWhile(Char::isDigit).toLongOrNull() ?: 0L
        }
        val pre = if (preText.isEmpty()) emptyList() else preText.split('.', '-')
        return Parsed(core, pre)
    }

    private fun comparePreRelease(a: List<String>, b: List<String>): Int {
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
