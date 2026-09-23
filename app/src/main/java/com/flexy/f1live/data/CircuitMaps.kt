package com.flexy.f1live.data

import com.flexy.f1live.model.RaceWeekend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.net.URLEncoder

/**
 * Track maps for a race weekend.
 *
 * The primary source is F1's own media CDN, which serves one transparent 16:9 PNG per circuit under
 * a fixed path. The `{Name}` segment is *not* derivable from the country: it is a hand-maintained
 * slug ("Baku" for Azerbaijan, "Great_Britain" for the UK, "USA" for Austin but "Miami" and
 * "Las_Vegas" for the other two American rounds). Every entry in [F1_MAP_NAMES] below was verified
 * to answer HTTP 200; anything not in the table returns null rather than a guess, because a wrong
 * guess is a 404 that Coil would silently show as a blank card. Sepang and Madrid genuinely have no
 * map on the CDN, so they fall through to Wikipedia.
 */
object CircuitMaps {

    private const val F1_MAP_BASE =
        "https://media.formula1.com/image/upload/f_auto,c_limit,w_1440,q_auto/f_auto/q_auto/" +
            "content/dam/fom-website/2018-redesign-assets/Circuit%20maps%2016x9/"

    /** The F1 media CDN map for this weekend, or null when the CDN has no verified name for it. */
    fun f1MapUrl(weekend: RaceWeekend): String? = f1MapName(weekend)?.let { F1_MAP_BASE + it + "_Circuit" }

    /**
     * The `{Name}` slug for a weekend. Circuit-specific rules come first: two rounds can share a
     * country (Barcelona and Madrid are both "Spain"; Miami, Austin and Las Vegas are all "USA")
     * and only the circuit tells them apart.
     */
    fun f1MapName(weekend: RaceWeekend): String? {
        val circuit = (weekend.circuitName + " " + weekend.locality + " " + weekend.name).lowercase()
        CIRCUIT_RULES.firstOrNull { (needle, _) -> circuit.contains(needle) }?.let { (_, name) ->
            return name.takeIf { it.isNotEmpty() }
        }
        return F1_MAP_NAMES[weekend.country.trim().lowercase()]
    }

    /**
     * Circuit-level overrides, matched against "circuitName locality raceName". An empty name means
     * "the CDN has no map for this one" - Sepang and the Madring both answer 404.
     */
    private val CIRCUIT_RULES: List<Pair<String, String>> = listOf(
        "madring" to "",
        "madrid" to "",
        "sepang" to "",
        "imola" to "Emilia_Romagna",
        "enzo e dino" to "Emilia_Romagna",
        "emilia" to "Emilia_Romagna",
        "miami" to "Miami",
        "las vegas" to "Las_Vegas",
        "americas" to "USA",
        "austin" to "USA",
        "indianapolis" to "",
        "interlagos" to "Brazil",
        "carlos pace" to "Brazil",
        "baku" to "Baku",
        "yas marina" to "Abu_Dhabi",
        "monza" to "Italy",
        "mugello" to "",
        "portimao" to "Portugal",
        "algarve" to "Portugal",
        "estoril" to "Portugal",
        "barcelona" to "Spain",
        "catalunya" to "Spain",
        "jeddah" to "Saudi_Arabia",
        "lusail" to "Qatar",
        "losail" to "Qatar",
        "marina bay" to "Singapore",
        "zandvoort" to "Netherlands",
        "silverstone" to "Great_Britain",
        "spa-francorchamps" to "Belgium",
        "hungaroring" to "Hungary",
        "red bull ring" to "Austria",
        "suzuka" to "Japan",
        "shanghai" to "China",
        "gilles villeneuve" to "Canada",
        "hermanos rodr" to "Mexico",
        "albert park" to "Australia",
        "sakhir" to "Bahrain",
    )

    /** Country (as Ergast spells it) -> verified CDN slug. */
    private val F1_MAP_NAMES: Map<String, String> = mapOf(
        "italy" to "Italy",
        "netherlands" to "Netherlands",
        "spain" to "Spain",
        "bahrain" to "Bahrain",
        "australia" to "Australia",
        "azerbaijan" to "Baku",
        "uae" to "Abu_Dhabi",
        "united arab emirates" to "Abu_Dhabi",
        "usa" to "USA",
        "united states" to "USA",
        "united states of america" to "USA",
        "uk" to "Great_Britain",
        "united kingdom" to "Great_Britain",
        "great britain" to "Great_Britain",
        "england" to "Great_Britain",
        "saudi arabia" to "Saudi_Arabia",
        "mexico" to "Mexico",
        "brazil" to "Brazil",
        "qatar" to "Qatar",
        "singapore" to "Singapore",
        "japan" to "Japan",
        "china" to "China",
        "canada" to "Canada",
        "austria" to "Austria",
        "hungary" to "Hungary",
        "belgium" to "Belgium",
        "monaco" to "Monaco",
        "portugal" to "Portugal",
    )

    /**
     * F1's plain track outline - one clean line on transparency, no DRS zones, turn numbers or
     * labels, and the current layout (the 2018 "Track icons" set still has the old Yas Marina,
     * Singapore and Barcelona). Served per season as `{year}track{slug}`; seasons after the newest
     * verified set reuse it. Null for circuits without a verified slug.
     */
    fun f1OutlineUrl(weekend: RaceWeekend): String? {
        val slug = outlineSlug(weekend) ?: return null
        val year = weekend.season.coerceIn(OUTLINE_FIRST_YEAR, OUTLINE_LATEST_YEAR)
        return "https://media.formula1.com/image/upload/f_png,w_640/common/f1/$year/track/${year}track$slug.png"
    }

    /**
     * F1's official detailed map from the same per-season track set: corner numbers, sector colours
     * and the overtake/DRS markings on a transparent background (white track edge, so it reads on
     * dark and light cards alike). Never tinted - the colours are the point. Null without a slug.
     */
    fun f1DetailedMapUrl(weekend: RaceWeekend): String? {
        val slug = outlineSlug(weekend) ?: return null
        val year = weekend.season.coerceIn(OUTLINE_FIRST_YEAR, OUTLINE_LATEST_YEAR)
        return "https://media.formula1.com/image/upload/f_png,w_1200/common/f1/$year/track/" +
            "${year}track${slug}detailed.png"
    }

    fun outlineSlug(weekend: RaceWeekend): String? {
        val circuit = (weekend.circuitName + " " + weekend.locality + " " + weekend.name).lowercase()
        OUTLINE_RULES.firstOrNull { (needle, _) -> circuit.contains(needle) }?.let { (_, slug) ->
            return slug.takeIf { it.isNotEmpty() }
        }
        return null
    }

    private const val OUTLINE_FIRST_YEAR = 2025
    private const val OUTLINE_LATEST_YEAR = 2026

    /** "circuitName locality raceName" needle -> outline slug; every slug answered HTTP 200. */
    private val OUTLINE_RULES: List<Pair<String, String>> = listOf(
        "sepang" to "",
        "madring" to "madring",
        "madrid" to "madring",
        "imola" to "imola",
        "enzo e dino" to "imola",
        "emilia" to "imola",
        "miami" to "miami",
        "las vegas" to "lasvegas",
        "americas" to "austin",
        "austin" to "austin",
        "interlagos" to "interlagos",
        "carlos pace" to "interlagos",
        "são paulo" to "interlagos",
        "baku" to "baku",
        "azerbaijan" to "baku",
        "yas marina" to "yasmarinacircuit",
        "abu dhabi" to "yasmarinacircuit",
        "monza" to "monza",
        "barcelona" to "catalunya",
        "catalunya" to "catalunya",
        "jeddah" to "jeddah",
        "lusail" to "lusail",
        "losail" to "lusail",
        "qatar" to "lusail",
        "marina bay" to "singapore",
        "singapore" to "singapore",
        "zandvoort" to "zandvoort",
        "silverstone" to "silverstone",
        "spa-francorchamps" to "spafrancorchamps",
        "belgian" to "spafrancorchamps",
        "hungaroring" to "hungaroring",
        "hungarian" to "hungaroring",
        "red bull ring" to "spielberg",
        "spielberg" to "spielberg",
        "austrian" to "spielberg",
        "suzuka" to "suzuka",
        "japanese" to "suzuka",
        "shanghai" to "shanghai",
        "chinese" to "shanghai",
        "gilles villeneuve" to "montreal",
        "montreal" to "montreal",
        "hermanos rodr" to "mexicocity",
        "mexico city" to "mexicocity",
        "albert park" to "melbourne",
        "melbourne" to "melbourne",
        "sakhir" to "sakhir",
        "bahrain" to "sakhir",
        "monaco" to "montecarlo",
        "monte carlo" to "montecarlo",
    )

    /** Number of corners of the circuit's current layout, or null when not in the table. */
    fun turnsOf(weekend: RaceWeekend): Int? = outlineSlug(weekend)?.let { TURNS[it] }

    /**
     * Corners per outline slug, as formula1.com's circuit pages count them for the current layouts
     * (Albert Park after 2022, Barcelona without the chicane, Marina Bay after 2023, Yas Marina
     * after 2021). A circuit missing here shows no count rather than a guess.
     */
    private val TURNS: Map<String, Int> = mapOf(
        "melbourne" to 14,
        "shanghai" to 16,
        "suzuka" to 18,
        "sakhir" to 15,
        "jeddah" to 27,
        "miami" to 19,
        "montreal" to 14,
        "montecarlo" to 19,
        "catalunya" to 14,
        "spielberg" to 10,
        "silverstone" to 18,
        "hungaroring" to 14,
        "spafrancorchamps" to 19,
        "zandvoort" to 14,
        "monza" to 11,
        "madring" to 22,
        "baku" to 20,
        "singapore" to 19,
        "austin" to 20,
        "mexicocity" to 17,
        "interlagos" to 15,
        "lasvegas" to 17,
        "lusail" to 16,
        "yasmarinacircuit" to 16,
        "imola" to 19,
    )

    /** Cache key for a resolved map URL. */
    fun key(weekend: RaceWeekend): String = "${weekend.season}_${weekend.round}"

    /** `https://en.wikipedia.org/wiki/Circuit_Zandvoort` -> `Circuit_Zandvoort`. */
    fun wikiTitleOf(url: String?): String? {
        val marker = "/wiki/"
        val index = url?.indexOf(marker) ?: return null
        if (index < 0) return null
        val title = url.substring(index + marker.length).substringBefore('#').trim()
        return title.takeIf { it.isNotEmpty() }
    }

    fun wikiPageImageUrl(title: String): String =
        "https://en.wikipedia.org/w/api.php?action=query&prop=pageimages&pithumbsize=1200" +
            "&format=json&titles=" + URLEncoder.encode(title, "UTF-8")

    /** `query.pages.*.thumbnail.source` out of the pageimages response. */
    fun parseWikiThumbnail(root: JsonObject): String? {
        val pages = root["query"] as? JsonObject ?: return null
        val byId = pages["pages"] as? JsonObject ?: return null
        for ((_, page) in byId) {
            val thumbnail = (page as? JsonObject)?.get("thumbnail") as? JsonObject ?: continue
            val source = (thumbnail["source"] as? JsonPrimitive)?.content
            if (!source.isNullOrBlank()) return source
        }
        return null
    }
}

/**
 * Resolves and remembers one map URL per weekend: the F1 CDN slug when there is one, otherwise the
 * circuit's Wikipedia page image (one API call, then cached on disk forever).
 */
class CircuitMapResolver(
    private val http: OkHttpClient,
    private val store: CircuitMapStore,
) {

    private val mutex = Mutex()
    private var memory: MutableMap<String, String>? = null

    /** Non-suspending peek for Compose: the CDN URL is free, a cached Wikipedia one is too. */
    fun cached(weekend: RaceWeekend): String? =
        CircuitMaps.f1MapUrl(weekend) ?: memory?.get(CircuitMaps.key(weekend))

    suspend fun resolve(weekend: RaceWeekend): String? {
        CircuitMaps.f1MapUrl(weekend)?.let { return it }

        val key = CircuitMaps.key(weekend)
        val cache = mutex.withLock {
            memory ?: store.read().toMutableMap().also { memory = it }
        }
        cache[key]?.let { return it }

        val title = CircuitMaps.wikiTitleOf(weekend.circuitWikiUrl) ?: return null
        val resolved = runCatching {
            CircuitMaps.parseWikiThumbnail(
                http.getJsonObject(CircuitMaps.wikiPageImageUrl(title), WIKI_USER_AGENT),
            )
        }.getOrNull() ?: return null

        mutex.withLock {
            cache[key] = resolved
            store.write(cache.toMap())
        }
        return resolved
    }

    private companion object {
        /** Wikipedia rejects anonymous clients; identify the app as their policy asks. */
        const val WIKI_USER_AGENT = "Laply/1.0 (Android; +https://github.com/Spottq/Laply)"
    }
}
