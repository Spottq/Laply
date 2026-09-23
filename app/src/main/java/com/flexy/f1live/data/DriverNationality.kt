package com.flexy.f1live.data

/**
 * The live feed's DriverList carries no nationality, so driver flags come from this
 * static TLA -> ISO 3166-1 alpha-2 (lowercase) table. Unknown TLAs resolve to null.
 */
object DriverNationality {
    private val byTla: Map<String, String> = mapOf(
        // 2026 grid
        "ALB" to "th", "ALO" to "es", "ANT" to "it", "BEA" to "gb", "BOR" to "br",
        "BOT" to "fi", "COL" to "ar", "GAS" to "fr", "HAM" to "gb", "HUL" to "de",
        "LAW" to "nz", "LEC" to "mc", "LIN" to "gb", "NOR" to "gb", "OCO" to "fr",
        "PER" to "mx", "PIA" to "au", "RUS" to "gb", "SAI" to "es", "STR" to "ca",
        "TSU" to "jp", "VER" to "nl",
        // recent drivers / reserves
        "HAD" to "fr", "DOO" to "au", "ZHO" to "cn", "MAG" to "dk", "RIC" to "au",
        "SAR" to "us", "BOT" to "fi", "DEV" to "nl", "VET" to "de", "RAI" to "fi",
        "MSC" to "de", "LAT" to "ca", "GIO" to "it", "KUB" to "pl", "GRO" to "fr",
        "KVY" to "ru", "MAZ" to "ru", "AIT" to "gb", "FIT" to "br", "DRU" to "br",
        "BRO" to "gb", "IWA" to "jp", "ARO" to "es", "HIR" to "jp", "CRA" to "gb",
        "VES" to "nl", "POU" to "fr", "MAR" to "it", "FOR" to "gb", "BEG" to "fr",
    )

    fun forTla(tla: String?): String? = tla?.uppercase()?.let(byTla::get)
}
