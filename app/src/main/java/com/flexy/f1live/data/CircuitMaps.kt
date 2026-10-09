package com.flexy.f1live.data

import com.flexy.f1live.model.RaceWeekend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.net.URLEncoder

object CircuitMaps {

    private const val F1_MAP_BASE =
        "https://media.formula1.com/image/upload/f_auto,c_limit,w_1440,q_auto/f_auto/q_auto/" +
            "content/dam/fom-website/2018-redesign-assets/Circuit%20maps%2016x9/"

    fun f1MapUrl(weekend: RaceWeekend): String? = f1MapName(weekend)?.let { F1_MAP_BASE + it + "_Circuit" }

    fun f1MapName(weekend: RaceWeekend): String? {
        val circuit = (weekend.circuitName + " " + weekend.locality + " " + weekend.name).lowercase()
        CIRCUIT_RULES.firstOrNull { (needle, _) -> circuit.contains(needle) }?.let { (_, name) ->
            return name.takeIf { it.isNotEmpty() }
        }
        return F1_MAP_NAMES[weekend.country.trim().lowercase()]
    }

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

    fun f1OutlineUrl(weekend: RaceWeekend): String? {
        val slug = outlineSlug(weekend) ?: return null
        val year = outlineYear(weekend, slug)
        return "https://media.formula1.com/image/upload/f_png,w_640/common/f1/$year/track/${year}track$slug.png"
    }

    fun f1DetailedMapUrl(weekend: RaceWeekend): String? {
        val slug = outlineSlug(weekend) ?: return null
        val year = outlineYear(weekend, slug)
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

    private val OUTLINE_SLUG_FIRST_YEAR: Map<String, Int> = mapOf(
        "kualalumpur" to 2026,
        "madring" to 2026,
    )

    private fun outlineYear(weekend: RaceWeekend, slug: String): Int {
        val first = OUTLINE_SLUG_FIRST_YEAR[slug] ?: OUTLINE_FIRST_YEAR
        return weekend.season.coerceIn(first, OUTLINE_LATEST_YEAR)
    }

    private val OUTLINE_RULES: List<Pair<String, String>> = listOf(
        "sepang" to "kualalumpur",
        "malaysia" to "kualalumpur",
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

    fun turnsOf(weekend: RaceWeekend): Int? = outlineSlug(weekend)?.let { TURNS[it] }

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
        "kualalumpur" to 15,
    )

    fun key(weekend: RaceWeekend): String = "${weekend.season}_${weekend.round}"

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

class CircuitMapResolver(
    private val http: OkHttpClient,
    private val store: CircuitMapStore,
) {

    private val mutex = Mutex()
    private var memory: MutableMap<String, String>? = null

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
        const val WIKI_USER_AGENT = "Laply/1.0 (Android; +https://github.com/Spottq/Laply)"
    }
}
