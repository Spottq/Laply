package com.flexy.f1live.data

/**
 * Team logos from F1's media CDN: a small transparent WEBP per constructor, drawn in white so it
 * reads on the app's dark surfaces.
 *
 * `https://media.formula1.com/image/upload/c_fit,h_400/q_auto/common/f1/{season}/{slug}/{season}{slug}logowhite.webp`
 *
 * The slug is F1's own, not the Ergast `constructorId` ("red_bull" is "redbullracing", "sauber" is
 * "audi" from 2026 on), so both the id table and the display-name table below are hand-mapped.
 * Unknown constructors return null and the UI keeps its plain coloured disc.
 */
object TeamLogos {

    private const val BASE = "https://media.formula1.com/image/upload/c_fit,h_400/q_auto/common/f1/"

    /** Ergast `constructorId` -> F1 media slug. */
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

    /**
     * Display names as the live feed and the results feeds spell them. Matched by normalised
     * substring so "Haas F1 Team", "Kick Sauber" and "Oracle Red Bull Racing" all resolve.
     */
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
