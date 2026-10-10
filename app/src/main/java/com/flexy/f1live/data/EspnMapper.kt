package com.flexy.f1live.data

import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.LiveSource
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

object EspnMapper {

    data class Athlete(
        val competitorId: String,
        val order: Int,
        val fullName: String,
        val shortName: String,
        val flagAlt: String?,
    )

    data class Competition(
        val eventId: String,
        val competitionId: String,
        val meetingName: String,
        val country: String,
        val city: String,
        val circuitName: String,
        val circuitId: String,
        val sessionName: String,
        val sessionKind: SessionKind,
        val state: String,
        val statusName: String = "",
        val startUtcMillis: Long?,
        val endUtcMillis: Long?,
        val athletes: List<Athlete>,
    ) {
        val isRace: Boolean get() = sessionKind == SessionKind.RACE || sessionKind == SessionKind.SPRINT
        val isQualifying: Boolean
            get() = sessionKind == SessionKind.QUALIFYING || sessionKind == SessionKind.SPRINT_QUALIFYING
    }

    data class Vehicle(
        val competitorId: String,
        val order: Int,
        val number: String,
        val manufacturer: String,
        val teamColorHex: String?,
        val statisticsRef: String?,
        val statusRef: String?,
    )

    data class CompetitorStatus(val name: String, val period: Int?)

    data class CompetitionStatus(val flag: String?, val period: Int?, val statusName: String)

    // ------------------------------------------------------------------ scoreboard

    fun parseScoreboard(root: JsonObject): List<Competition> {
        val events = root["events"].array()
        return events.mapNotNull { it as? JsonObject }.flatMap { event ->
            val circuit = event.obj("circuit")
            val address = circuit?.obj("address")
            val city = address?.string("city").orEmpty()
            val country = address?.string("country").orEmpty()
            val circuitName = circuit?.string("fullName").orEmpty()
            val circuitId = circuit?.string("id").orEmpty()
            val meetingName = stripSponsor(event.string("name").orEmpty())
            val eventId = event.string("id").orEmpty()

            event["competitions"].array().mapNotNull { it as? JsonObject }.mapNotNull { competition ->
                val competitionId = competition.string("id") ?: return@mapNotNull null
                val type = competition.obj("type")
                val typeText = type?.string("text").orEmpty()
                val abbreviation = type?.string("abbreviation").orEmpty()
                val sessionName = sessionNameOf(typeText, abbreviation)
                val start = parseUtcMillis(competition.string("date"))
                Competition(
                    eventId = eventId,
                    competitionId = competitionId,
                    meetingName = meetingName,
                    country = country,
                    city = city,
                    circuitName = circuitName,
                    circuitId = circuitId,
                    sessionName = sessionName,
                    sessionKind = LiveStateParser.sessionKindOf(typeText, sessionName),
                    state = competition.obj("status")?.obj("type")?.string("state")
                        ?.trim()?.lowercase().orEmpty(),
                    statusName = competition.obj("status")?.obj("type")?.string("name").orEmpty(),
                    startUtcMillis = start,
                    endUtcMillis = parseUtcMillis(competition.string("endDate")),
                    athletes = parseAthletes(competition),
                )
            }
        }
    }

    private fun parseAthletes(competition: JsonObject): List<Athlete> =
        competition["competitors"].array().mapNotNull { it as? JsonObject }.mapNotNull { competitor ->
            val id = competitor.string("id") ?: return@mapNotNull null
            val athlete = competitor.obj("athlete")
            Athlete(
                competitorId = id,
                order = competitor.string("order")?.toIntOrNull() ?: 0,
                fullName = athlete?.string("fullName") ?: athlete?.string("displayName").orEmpty(),
                shortName = athlete?.string("shortName").orEmpty(),
                flagAlt = athlete?.obj("flag")?.string("alt"),
            )
        }.sortedBy { if (it.order > 0) it.order else Int.MAX_VALUE }

    fun pickLive(competitions: List<Competition>): Competition? =
        competitions.filter { it.state == "in" }
            .maxByOrNull { it.startUtcMillis ?: Long.MIN_VALUE }

    fun pickLastCompleted(competitions: List<Competition>): Competition? {
        val finished = competitions.filter { it.state == "post" }
        if (finished.isEmpty()) return null
        val latestEventId = finished
            .groupBy { it.eventId }
            .maxByOrNull { (_, sessions) ->
                sessions.maxOf { it.startUtcMillis ?: Long.MIN_VALUE }
            }
            ?.key
        return finished
            .filter { it.eventId == latestEventId }
            .maxByOrNull { it.startUtcMillis ?: Long.MIN_VALUE }
    }

    // ------------------------------------------------------------------ core API

    fun parseCompetitors(root: JsonObject): List<Vehicle> =
        root["items"].array().mapNotNull { it as? JsonObject }.mapNotNull { item ->
            val id = item.string("id") ?: return@mapNotNull null
            val vehicle = item.obj("vehicle")
            Vehicle(
                competitorId = id,
                order = item.string("order")?.toIntOrNull() ?: 0,
                number = vehicle?.string("number").orEmpty(),
                manufacturer = vehicle?.string("manufacturer").orEmpty(),
                teamColorHex = vehicle?.string("teamColor")?.removePrefix("#")?.takeIf { it.isNotBlank() },
                statisticsRef = item.obj("statistics")?.string(REF)?.let(::httpsRef),
                statusRef = item.obj("status")?.string(REF)?.let(::httpsRef),
            )
        }

    private const val REF = "\$ref"

    fun httpsRef(ref: String): String =
        if (ref.startsWith("http://")) "https://" + ref.removePrefix("http://") else ref

    fun parseStatistics(root: JsonObject): Map<String, String> {
        val categories = root.obj("splits")?.get("categories").array()
        val stats = LinkedHashMap<String, String>()
        for (category in categories.mapNotNull { it as? JsonObject }) {
            for (stat in category["stats"].array().mapNotNull { it as? JsonObject }) {
                val name = stat.string("name") ?: continue
                val value = stat.string("displayValue") ?: stat.string("value") ?: continue
                if (!stats.containsKey(name)) stats[name] = value
            }
        }
        return stats
    }

    fun parseCompetitionStatus(root: JsonObject): CompetitionStatus = CompetitionStatus(
        flag = root.string("flag"),
        period = root.string("period")?.toIntOrNull(),
        statusName = root.obj("type")?.string("name").orEmpty(),
    )

    fun parseCircuitLaps(root: JsonObject): Int? = root.string("laps")?.toIntOrNull()?.takeIf { it > 0 }

    fun trackFlagOf(raw: String?): TrackFlag = when (raw?.trim()?.uppercase()) {
        "GREEN", "WHITE", "CHEQUERED", "CHECKERED" -> TrackFlag.GREEN
        "YELLOW", "DOUBLE YELLOW" -> TrackFlag.YELLOW
        "RED" -> TrackFlag.RED
        "SAFETY CAR", "SC", "SAFETYCAR" -> TrackFlag.SC
        "VIRTUAL SAFETY CAR", "VSC" -> TrackFlag.VSC
        else -> TrackFlag.UNKNOWN
    }

    fun parseCompetitorStatus(root: JsonObject): CompetitorStatus = CompetitorStatus(
        name = root.obj("type")?.string("name")
            ?: root.obj("type")?.string("description").orEmpty(),
        period = root.string("period")?.toIntOrNull(),
    )

    // ------------------------------------------------------------------ state

    fun buildState(
        competition: Competition,
        vehicles: Map<String, Vehicle>,
        statistics: Map<String, Map<String, String>>,
        statuses: Map<String, CompetitorStatus>,
        nowUtcMillis: Long,
        competitionStatus: CompetitionStatus? = null,
        totalLaps: Int? = null,
    ): LiveSessionState {
        val drivers = competition.athletes.map { athlete ->
            driverTiming(
                competition = competition,
                athlete = athlete,
                vehicle = vehicles[athlete.competitorId],
                stats = statistics[athlete.competitorId].orEmpty(),
                status = statuses[athlete.competitorId],
            )
        }.sortedWith(
            compareBy(
                { if (it.position > 0) it.position else Int.MAX_VALUE },
                { it.racingNumber.toIntOrNull() ?: Int.MAX_VALUE },
            ),
        )

        val leaderLap = maxOf(
            drivers.firstOrNull()?.numberOfLaps ?: 0,
            competitionStatus?.period ?: 0,
        ).let { lap -> totalLaps?.takeIf { it > 0 }?.let { lap.coerceAtMost(it) } ?: lap }

        return LiveSessionState(
            isConnected = true,
            meetingName = competition.meetingName,
            meetingCountry = competition.country,
            meetingLocation = competition.city,
            circuitShortName = competition.city,
            sessionName = competition.sessionName,
            sessionKind = competition.sessionKind,
            sessionPart = null,
            status = statusOf(competition.state, competition.statusName),
            trackFlag = trackFlagOf(competitionStatus?.flag),
            airTempC = null,
            trackTempC = null,
            humidityPct = null,
            rainfall = null,
            currentLap = leaderLap.takeIf { competition.isRace && it > 0 },
            totalLaps = totalLaps?.takeIf { competition.isRace },
            drivers = drivers,
            raceControl = emptyList(),
            lastUpdateUtcMillis = nowUtcMillis,
            source = LiveSource.ESPN,
        )
    }

    private fun driverTiming(
        competition: Competition,
        athlete: Athlete,
        vehicle: Vehicle?,
        stats: Map<String, String>,
        status: CompetitorStatus?,
    ): DriverTiming {
        val full = athlete.fullName.trim()
        val firstName = full.substringBefore(' ', full).trim()
        val lastName = full.substringAfter(' ', "").trim()
        val tla = tlaOf(full)
        val statusName = status?.name?.uppercase().orEmpty()

        return DriverTiming(
            position = athlete.order.takeIf { it > 0 } ?: vehicle?.order ?: 0,
            racingNumber = vehicle?.number?.takeIf { it.isNotBlank() } ?: athlete.competitorId,
            tla = tla,
            firstName = firstName,
            lastName = lastName,
            shortName = athlete.shortName.ifBlank { full },
            teamName = vehicle?.manufacturer.orEmpty(),
            teamColorHex = vehicle?.teamColorHex,
            headshotUrl = DriverHeadshots.forTla(tla),
            countryCode = DriverNationality.forTla(tla) ?: countryCodeOfFlagAlt(athlete.flagAlt),
            bestLapTime = bestLapTimeOf(competition, stats),
            lastLapTime = "",
            gapToLeader = if (competition.isRace) gapOf(stats) else "",
            interval = "",
            sectors = emptyList(),
            inPit = statusName.contains("PIT"),
            pitOut = false,
            retired = statusName.contains("RETIRED") || statusName.contains("DNF"),
            stopped = false,
            knockedOut = false,
            numberOfLaps = stats["lapsCompleted"]?.toIntOrNull() ?: status?.period ?: 0,
            numberOfPitStops = stats["pitsTaken"]?.toIntOrNull() ?: 0,
            tyreCompound = null,
        )
    }

    fun gapOf(stats: Map<String, String>): String =
        listOf("gapToLeader", "behindTime")
            .firstNotNullOfOrNull { stats[it]?.trim()?.takeIf(::isRealTime) }
            .orEmpty()

    fun bestLapTimeOf(competition: Competition, stats: Map<String, String>): String = when {
        competition.isRace -> ""
        competition.isQualifying ->
            listOf("qual3TimeMS", "qual2TimeMS", "qual1TimeMS")
                .firstNotNullOfOrNull { stats[it]?.takeIf(::isRealTime) }
                .orEmpty()
        else -> stats["totalTime"]?.takeIf(::isRealTime).orEmpty()
    }

    private fun isRealTime(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isNotEmpty() && trimmed != "0.000" && trimmed != "0" && trimmed != "--"
    }

    fun statusOf(state: String, statusName: String = ""): SessionStatus {
        val name = statusName.trim().uppercase()
        if (name.contains("COMPLETE") || name.contains("FINAL") || name.contains("CLASSIFIED")) {
            return SessionStatus.FINISHED
        }
        return when (state.trim().lowercase()) {
            "in" -> SessionStatus.STARTED
            "post" -> SessionStatus.FINISHED
            "pre" -> SessionStatus.INACTIVE
            else -> SessionStatus.UNKNOWN
        }
    }

    // ------------------------------------------------------------------ naming

    fun stripSponsor(name: String): String {
        val words = name.trim().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return name.trim()
        val gp = words.indices.firstOrNull { index ->
            words[index].equals("Grand", ignoreCase = true) &&
                words.getOrNull(index + 1)?.equals("Prix", ignoreCase = true) == true
        } ?: return name.trim()
        if (gp == 0) return name.trim()
        var from = gp - 1
        if (from > 0 && words[from - 1].trimEnd(',').lowercase() in TWO_WORD_QUALIFIER_STARTS) from -= 1
        return words.subList(from, words.size).joinToString(" ")
    }

    private val TWO_WORD_QUALIFIER_STARTS = setOf(
        "united", "las", "abu", "sao", "são", "emilia", "saudi", "mexico", "new", "san", "great",
    )

    fun sessionNameOf(typeText: String, abbreviation: String): String =
        when (abbreviation.trim().uppercase()) {
            "FP1" -> "Practice 1"
            "FP2" -> "Practice 2"
            "FP3" -> "Practice 3"
            "SS" -> "Sprint Qualifying"
            "SR" -> "Sprint"
            "QUAL" -> "Qualifying"
            "RACE" -> "Race"
            else -> typeText.trim().ifBlank { abbreviation.trim() }
        }

    fun tlaOf(fullName: String): String {
        val words = fullName.trim().split(' ', '-')
            .map { it.trim().trim('.', ',') }
            .filter { it.isNotBlank() && it.lowercase() !in NAME_SUFFIXES }
        val last = words.lastOrNull().orEmpty()
        return last.filter { it.isLetter() }.take(3).uppercase()
    }

    private val NAME_SUFFIXES = setOf("jr", "jnr", "sr", "snr", "ii", "iii", "iv")

    fun countryCodeOfFlagAlt(alt: String?): String? {
        val key = alt?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return FLAG_ALT_TO_ALPHA2[key]
    }

    private val FLAG_ALT_TO_ALPHA2: Map<String, String> = mapOf(
        "britain" to "gb", "great britain" to "gb", "united kingdom" to "gb", "england" to "gb",
        "netherlands" to "nl", "italy" to "it", "france" to "fr", "argentina" to "ar",
        "monaco" to "mc", "australia" to "au", "spain" to "es", "germany" to "de",
        "canada" to "ca", "mexico" to "mx", "japan" to "jp", "thailand" to "th",
        "brazil" to "br", "finland" to "fi", "new zealand" to "nz", "united states" to "us",
        "usa" to "us", "denmark" to "dk", "china" to "cn", "switzerland" to "ch",
        "austria" to "at", "belgium" to "be", "poland" to "pl", "sweden" to "se",
        "russia" to "ru", "ireland" to "ie", "portugal" to "pt", "south africa" to "za",
    )

    // ------------------------------------------------------------------ utilities

    fun parseUtcMillis(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(value).toEpochMilli()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeIf { it != "null" }

    private fun JsonElement?.array(): List<JsonElement> = this as? JsonArray ?: emptyList()
}
