package com.flexy.f1live.data

import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.LiveSource
import com.flexy.f1live.model.RaceControlMessage
import com.flexy.f1live.model.SectorTiming
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.model.TrackFlag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Turns the merged SignalR snapshot (a [JsonObject] keyed by topic) into a [LiveSessionState].
 *
 * Pure JVM (no Android classes) so it can be unit tested against a captured snapshot.
 */
object LiveStateParser {

    fun parse(
        root: JsonObject,
        isConnected: Boolean = true,
        nowUtcMillis: Long = System.currentTimeMillis(),
    ): LiveSessionState {
        val sessionInfo = root.obj("SessionInfo")
        val meeting = sessionInfo?.obj("Meeting")
        val timingData = root.obj("TimingData")

        val sessionType = sessionInfo?.string("Type").orEmpty()
        val sessionName = sessionInfo?.string("Name").orEmpty()
        val kind = sessionKindOf(sessionType, sessionName)
        val sessionPart = timingData?.int("SessionPart")
            ?.takeIf { kind == SessionKind.QUALIFYING || kind == SessionKind.SPRINT_QUALIFYING }

        val weather = root.obj("WeatherData")
        val lapCount = root.obj("LapCount")

        return LiveSessionState(
            isConnected = isConnected,
            meetingName = meeting?.string("Name").orEmpty(),
            meetingCountry = meeting?.obj("Country")?.string("Name").orEmpty(),
            meetingLocation = meeting?.string("Location").orEmpty(),
            circuitShortName = meeting?.obj("Circuit")?.string("ShortName").orEmpty(),
            sessionName = sessionName,
            sessionKind = kind,
            sessionPart = sessionPart,
            status = statusOf(
                root.obj("SessionStatus")?.string("Status") ?: sessionInfo?.string("SessionStatus"),
            ),
            trackFlag = trackFlagOf(root.obj("TrackStatus")?.string("Status")),
            airTempC = weather?.string("AirTemp")?.toDoubleOrNull(),
            trackTempC = weather?.string("TrackTemp")?.toDoubleOrNull(),
            humidityPct = weather?.string("Humidity")?.toDoubleOrNull(),
            rainfall = weather?.string("Rainfall")?.let { it.trim() !in NO_RAIN },
            currentLap = lapCount?.int("CurrentLap"),
            totalLaps = lapCount?.int("TotalLaps"),
            drivers = parseDrivers(root, sessionPart),
            raceControl = parseRaceControl(root),
            lastUpdateUtcMillis = nowUtcMillis,
            source = LiveSource.F1_LIVE_TIMING,
            trackUtcOffsetMinutes = utcOffsetMinutesOf(sessionInfo?.string("GmtOffset")),
        )
    }

    /** "08:00:00" -> 480, "-04:00:00" -> -240; null for anything else. */
    fun utcOffsetMinutesOf(raw: String?): Int? {
        val match = OffsetPattern.matchEntire(raw?.trim().orEmpty()) ?: return null
        val (sign, hours, minutes) = match.destructured
        val total = hours.toInt() * 60 + minutes.toInt()
        return if (sign == "-") -total else total
    }

    private val OffsetPattern = Regex("""([+-]?)(\d{1,2}):(\d{2})(?::\d{2})?""")

    private val NO_RAIN = setOf("", "0", "0.0", "false")

    // ------------------------------------------------------------------ drivers

    private fun parseDrivers(root: JsonObject, sessionPart: Int?): List<DriverTiming> {
        val lines = root.obj("TimingData")?.obj("Lines") ?: return emptyList()
        val driverList = root.obj("DriverList")
        val appLines = root.obj("TimingAppData")?.obj("Lines")
        val statsLines = root.obj("TimingStats")?.obj("Lines")

        return lines.entries
            .mapNotNull { (racingNumber, element) ->
                val line = element as? JsonObject ?: return@mapNotNull null
                driverTiming(
                    racingNumber = racingNumber,
                    line = line,
                    driver = driverList?.obj(racingNumber),
                    appData = appLines?.obj(racingNumber),
                    stats = statsLines?.obj(racingNumber),
                    sessionPart = sessionPart,
                )
            }
            .sortedWith(
                compareBy(
                    { if (it.position > 0) it.position else Int.MAX_VALUE },
                    { it.racingNumber.toIntOrNull() ?: Int.MAX_VALUE },
                ),
            )
    }

    private fun driverTiming(
        racingNumber: String,
        line: JsonObject,
        driver: JsonObject?,
        appData: JsonObject?,
        stats: JsonObject?,
        sessionPart: Int?,
    ): DriverTiming {
        val position = line.int("Line")
            ?: line.string("Position")?.toIntOrNull()
            ?: driver?.int("Line")
            ?: 0

        val firstName = driver?.string("FirstName").orEmpty()
        val lastName = driver?.string("LastName").orEmpty()
        val stat = line["Stats"]?.indexedList()?.getOrNull((sessionPart ?: 1) - 1) as? JsonObject

        return DriverTiming(
            position = position,
            racingNumber = racingNumber,
            tla = driver?.string("Tla").orEmpty(),
            firstName = firstName,
            lastName = lastName,
            shortName = shortNameOf(firstName, lastName, driver),
            teamName = driver?.string("TeamName").orEmpty(),
            teamColorHex = driver?.string("TeamColour")?.removePrefix("#")?.takeIf { it.isNotBlank() },
            headshotUrl = DriverHeadshots.resolve(
                driver?.string("Tla"),
                driver?.string("HeadshotUrl")?.takeIf { it.isNotBlank() },
            ),
            countryCode = countryCodeOf(driver?.string("CountryCode"))
                ?: DriverNationality.forTla(driver?.string("Tla")),
            bestLapTime = line.obj("BestLapTime")?.string("Value").orEmpty(),
            lastLapTime = line.obj("LastLapTime")?.string("Value").orEmpty(),
            gapToLeader = line.string("GapToLeader")
                ?: stat?.string("TimeDiffToFastest")
                ?: line.string("TimeDiffToFastest")
                ?: "",
            interval = line.obj("IntervalToPositionAhead")?.string("Value")
                ?: line.string("IntervalToPositionAhead")
                ?: stat?.string("TimeDifftoPositionAhead")
                ?: stat?.string("TimeDiffToPositionAhead")
                ?: line.string("TimeDifftoPositionAhead")
                ?: line.string("TimeDiffToPositionAhead")
                ?: "",
            sectors = parseSectors(line["Sectors"]),
            inPit = line.bool("InPit") ?: false,
            pitOut = line.bool("PitOut") ?: false,
            retired = line.bool("Retired") ?: false,
            stopped = line.bool("Stopped") ?: false,
            knockedOut = line.bool("KnockedOut") ?: false,
            numberOfLaps = line.int("NumberOfLaps") ?: 0,
            numberOfPitStops = line.int("NumberOfPitStops") ?: 0,
            tyreCompound = currentCompound(appData),
            // Position 1 among everyone's personal bests is the session's fastest lap - the purple
            // time on an F1 timing tower.
            fastestLap = stats?.obj("PersonalBestLapTime")?.int("Position") == 1,
            bestSectors = parseBestSectors(stats?.get("BestSectors")),
        )
    }

    /** "Lando" + "Norris" -> "L. Norris". */
    private fun shortNameOf(firstName: String, lastName: String, driver: JsonObject?): String = when {
        firstName.isNotBlank() && lastName.isNotBlank() ->
            firstName.trim().first().uppercaseChar() + ". " + lastName.trim()
        lastName.isNotBlank() -> lastName.trim()
        else -> driver?.string("BroadcastName") ?: driver?.string("Tla").orEmpty()
    }

    private fun parseSectors(element: JsonElement?): List<SectorTiming> =
        element?.indexedList().orEmpty().map { sector ->
            val obj = sector as? JsonObject
            // Value is cleared when a new lap starts (out/in laps); PreviousValue keeps the last one.
            SectorTiming(
                value = obj?.string("Value")?.takeIf { it.isNotBlank() }
                    ?: obj?.string("PreviousValue").orEmpty(),
                personalFastest = obj?.bool("PersonalFastest") ?: false,
                overallFastest = obj?.bool("OverallFastest") ?: false,
            )
        }

    /**
     * `TimingStats.Lines[n].BestSectors` - the driver's best time in each sector, with the
     * position it holds in the session. Position 1 is the session-best sector, everything else is
     * that driver's own best, which is exactly the personal/overall distinction the row draws.
     */
    private fun parseBestSectors(element: JsonElement?): List<SectorTiming> =
        element?.indexedList().orEmpty().map { sector ->
            val obj = sector as? JsonObject
            val value = obj?.string("Value").orEmpty()
            SectorTiming(
                value = value,
                personalFastest = value.isNotBlank(),
                overallFastest = obj?.int("Position") == 1,
            )
        }

    /** Last stint that actually names a compound ("SOFT" / "MEDIUM" / ...). */
    private fun currentCompound(appData: JsonObject?): String? =
        appData?.get("Stints")?.indexedList()
            ?.asReversed()
            ?.firstNotNullOfOrNull { stint ->
                (stint as? JsonObject)?.string("Compound")?.takeIf { it.isNotBlank() }
            }
            ?.uppercase()

    // ------------------------------------------------------------- race control

    private fun parseRaceControl(root: JsonObject): List<RaceControlMessage> {
        val messages = root.obj("RaceControlMessages")?.get("Messages")?.indexedList().orEmpty()
        return messages
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val text = obj.string("Message").orEmpty()
                if (text.isBlank()) return@mapNotNull null
                RaceControlMessage(
                    utcMillis = parseUtcMillis(obj.string("Utc")),
                    category = obj.string("Category").orEmpty(),
                    message = text,
                    flag = obj.string("Flag")?.takeIf { it.isNotBlank() },
                )
            }
            .sortedWith(compareBy(nullsFirst()) { it.utcMillis })
    }

    /**
     * Feed timestamps arrive both as full instants ("2026-09-05T14:33:08.789Z") and as zone-less
     * local date-times ("2026-09-05T13:50:42") which are UTC by convention.
     */
    fun parseUtcMillis(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    // -------------------------------------------------------------- enum mapping

    fun sessionKindOf(type: String, name: String): SessionKind {
        val n = name.trim().lowercase()
        val t = type.trim().lowercase()
        val sprint = n.contains("sprint") || t.contains("sprint")
        return when {
            n.contains("practice 1") || n == "fp1" -> SessionKind.PRACTICE1
            n.contains("practice 2") || n == "fp2" -> SessionKind.PRACTICE2
            n.contains("practice 3") || n == "fp3" -> SessionKind.PRACTICE3
            sprint && (n.contains("qualifying") || n.contains("shootout")) -> SessionKind.SPRINT_QUALIFYING
            sprint -> SessionKind.SPRINT
            n.contains("qualifying") || t == "qualifying" -> SessionKind.QUALIFYING
            n == "race" || t == "race" -> SessionKind.RACE
            t == "practice" || n.contains("practice") -> SessionKind.PRACTICE1
            else -> SessionKind.UNKNOWN
        }
    }

    fun statusOf(raw: String?): SessionStatus = when (raw?.trim()?.lowercase()) {
        "inactive" -> SessionStatus.INACTIVE
        "started" -> SessionStatus.STARTED
        "aborted" -> SessionStatus.ABORTED
        "finished" -> SessionStatus.FINISHED
        "finalised", "finalized" -> SessionStatus.FINALISED
        "ends" -> SessionStatus.ENDS
        else -> SessionStatus.UNKNOWN
    }

    fun trackFlagOf(raw: String?): TrackFlag = when (raw?.trim()) {
        "1" -> TrackFlag.GREEN
        "2" -> TrackFlag.YELLOW
        "4" -> TrackFlag.SC
        "5" -> TrackFlag.RED
        "6" -> TrackFlag.VSC
        "7" -> TrackFlag.VSC_ENDING
        else -> TrackFlag.UNKNOWN
    }

    /** IOC / ISO alpha-3 nationality code -> ISO 3166-1 alpha-2, lowercase; null when unknown. */
    fun countryCodeOf(raw: String?): String? {
        val code = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (code.length == 2) return code.lowercase()
        return COUNTRY_ALPHA3_TO_ALPHA2[code]
    }

    private val COUNTRY_ALPHA3_TO_ALPHA2: Map<String, String> = mapOf(
        "ARE" to "ae", "ARG" to "ar", "ARM" to "am", "AUS" to "au", "AUT" to "at",
        "AZE" to "az", "BEL" to "be", "BGR" to "bg", "BRA" to "br", "BRN" to "bh",
        "BUL" to "bg", "CAN" to "ca", "CHE" to "ch", "CHI" to "cl", "CHN" to "cn",
        "COL" to "co", "CRO" to "hr", "CZE" to "cz", "DEN" to "dk", "DEU" to "de",
        "DNK" to "dk", "ESP" to "es", "EST" to "ee", "FIN" to "fi", "FRA" to "fr",
        "GBR" to "gb", "GER" to "de", "GRC" to "gr", "GRE" to "gr", "HKG" to "hk",
        "HRV" to "hr", "HUN" to "hu", "IDN" to "id", "INA" to "id", "IND" to "in",
        "IRL" to "ie", "ISR" to "il", "ITA" to "it", "JPN" to "jp", "KOR" to "kr",
        "KSA" to "sa", "LAT" to "lv", "LTU" to "lt", "LUX" to "lu", "LVA" to "lv",
        "MAL" to "my", "MAR" to "ma", "MAS" to "my", "MCO" to "mc", "MEX" to "mx",
        "MON" to "mc", "MYS" to "my", "NED" to "nl", "NLD" to "nl", "NOR" to "no",
        "NZL" to "nz", "OMA" to "om", "OMN" to "om", "PER" to "pe", "PHI" to "ph",
        "POL" to "pl", "POR" to "pt", "PRT" to "pt", "QAT" to "qa", "ROU" to "ro",
        "RSA" to "za", "RUS" to "ru", "SAU" to "sa", "SGP" to "sg", "SIN" to "sg",
        "SLO" to "si", "SRB" to "rs", "SUI" to "ch", "SVK" to "sk", "SVN" to "si",
        "SWE" to "se", "THA" to "th", "TUR" to "tr", "UAE" to "ae", "UKR" to "ua",
        "URU" to "uy", "URY" to "uy", "USA" to "us", "VEN" to "ve", "VIE" to "vn",
        "VNM" to "vn", "ZAF" to "za",
    )

    // --------------------------------------------------------------- json utils

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.int(key: String): Int? = string(key)?.trim()?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        when (string(key)?.trim()?.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }

    /**
     * Lists arrive as JSON arrays in the snapshot but as index-keyed objects in deltas; a merged
     * document therefore keeps the object form for anything only ever seen as a delta.
     */
    private fun JsonElement.indexedList(): List<JsonElement> = when (this) {
        is JsonArray -> this
        is JsonObject -> entries
            .mapNotNull { (key, value) -> key.toIntOrNull()?.let { it to value } }
            .sortedBy { it.first }
            .map { it.second }
        else -> emptyList()
    }
}
