package com.flexy.f1live.data

object DriverNames {
    private val racingFirstNames: Map<String, String> = mapOf(
        "Andrea Kimi" to "Kimi",
    )

    fun racingFirstName(givenName: String): String =
        givenName.trim().let { racingFirstNames[it] ?: it }

    fun short(givenName: String, familyName: String): String {
        val first = racingFirstName(givenName)
        val last = familyName.trim()
        return if (first.isNotEmpty()) first.first().uppercaseChar() + ". " + last else last
    }
}
