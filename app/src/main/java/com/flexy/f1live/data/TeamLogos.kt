package com.flexy.f1live.data

object TeamLogos {

    private const val BASE = "https://media.formula1.com/image/upload/c_fit,h_400/q_auto/common/f1/"

    private val BY_CONSTRUCTOR_ID: Map<String, String> = mapOf(
        "mclaren" to "mclaren",
        "ferrari" to "ferrari",
        "mercedes" to "mercedes",
        "red_bull" to "redbullracing",
        "aston_martin" to "astonmartin",
        "alpine" to "alpine",
        "williams" to "williams",
        "rb" to "racingbulls",
        "racing_bulls" to "racingbulls",
        "alphatauri" to "racingbulls",
        "haas" to "haas",
        "audi" to "audi",
        "sauber" to "audi",
        "alfa" to "audi",
        "cadillac" to "cadillac",
    )

    private val BY_NAME_FRAGMENT: List<Pair<String, String>> = listOf(
        "red bull" to "redbullracing",
        "racing bulls" to "racingbulls",
        "alphatauri" to "racingbulls",
        "aston martin" to "astonmartin",
        "mclaren" to "mclaren",
        "ferrari" to "ferrari",
        "mercedes" to "mercedes",
        "alpine" to "alpine",
        "williams" to "williams",
        "haas" to "haas",
        "audi" to "audi",
        "sauber" to "audi",
        "cadillac" to "cadillac",
    )

    fun forConstructorId(id: String, season: Int = DEFAULT_SEASON): String? =
        BY_CONSTRUCTOR_ID[id.trim().lowercase()]?.let { url(it, season) }

    fun forTeamName(name: String, season: Int = DEFAULT_SEASON): String? {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return null
        return BY_NAME_FRAGMENT.firstOrNull { (fragment, _) -> key.contains(fragment) }
            ?.let { (_, slug) -> url(slug, season) }
    }

    private fun url(slug: String, season: Int): String =
        BASE + season + "/" + slug + "/" + season + slug + "logowhite.webp"

    const val DEFAULT_SEASON = 2026
}
