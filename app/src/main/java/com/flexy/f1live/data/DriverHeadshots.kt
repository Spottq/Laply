package com.flexy.f1live.data

import com.flexy.f1live.model.LiveSessionState
import java.text.Normalizer

object DriverHeadshots {
    private const val BASE = "https://media.formula1.com/d_driver_fallback_image.png/content/dam/fom-website/drivers/"

    private const val CURRENT_BASE =
        "https://media.formula1.com/image/upload/c_thumb,g_face,w_200,h_200,z_0.6/q_auto/v1740000001/common/f1/"

    private val byTla: Map<String, String> = mapOf(
        "ALB" to "A/ALEALB01_Alexander_Albon/alealb01",
        "ALO" to "F/FERALO01_Fernando_Alonso/feralo01",
        "ANT" to "K/ANDANT01_Kimi_Antonelli/andant01",
        "BEA" to "O/OLIBEA01_Oliver_Bearman/olibea01",
        "BOR" to "G/GABBOR01_Gabriel_Bortoleto/gabbor01",
        "BOT" to "V/VALBOT01_Valtteri_Bottas/valbot01",
        "COL" to "F/FRACOL01_Franco_Colapinto/fracol01",
        "GAS" to "P/PIEGAS01_Pierre_Gasly/piegas01",
        "HAM" to "L/LEWHAM01_Lewis_Hamilton/lewham01",
        "LAW" to "L/LIALAW01_Liam_Lawson/lialaw01",
        "LEC" to "C/CHALEC01_Charles_Leclerc/chalec01",
        "NOR" to "L/LANNOR01_Lando_Norris/lannor01",
        "OCO" to "E/ESTOCO01_Esteban_Ocon/estoco01",
        "PIA" to "O/OSCPIA01_Oscar_Piastri/oscpia01",
        "RUS" to "G/GEORUS01_George_Russell/georus01",
        "SAI" to "C/CARSAI01_Carlos_Sainz/carsai01",
        "STR" to "L/LANSTR01_Lance_Stroll/lanstr01",
        "TSU" to "Y/YUKTSU01_Yuki_Tsunoda/yuktsu01",
        "VER" to "M/MAXVER01_Max_Verstappen/maxver01",
        "HAD" to "I/ISAHAD01_Isack_Hadjar/isahad01",
        "DOO" to "J/JACDOO01_Jack_Doohan/jacdoo01",
        "ZHO" to "G/GUAZHO01_Guanyu_Zhou/guazho01",
        "MAG" to "K/KEVMAG01_Kevin_Magnussen/kevmag01",
        "RIC" to "D/DANRIC01_Daniel_Ricciardo/danric01",
        "SAR" to "L/LOGSAR01_Logan_Sargeant/logsar01",
    )

    private val current: Map<String, String> = mapOf(
        "LIN" to "2026/racingbulls/arvlin01/2026racingbullsarvlin01right.webp",
        "PER" to "2026/cadillac/serper01/2026cadillacserper01right.webp",
        "HUL" to "2026/audi/nichul01/2026audinichul01right.webp",
    )

    private fun key(tla: String?): String? =
        tla?.let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            ?.replace(CombiningMarks, "")
            ?.uppercase()

    private val CombiningMarks = Regex("\\p{Mn}+")

    private fun currentFor(tla: String?): String? =
        key(tla)?.let(current::get)?.let { CURRENT_BASE + it }

    fun forTla(tla: String?): String? =
        currentFor(tla)
            ?: key(tla)?.let(byTla::get)?.let { "$BASE$it.png.transform/1col/image.png" }

    fun resolve(tla: String?, url: String?): String? = currentFor(tla) ?: url

    fun refresh(state: LiveSessionState): LiveSessionState {
        if (state.drivers.none { currentFor(it.tla) != null }) return state
        return state.copy(
            drivers = state.drivers.map { driver ->
                driver.copy(headshotUrl = resolve(driver.tla, driver.headshotUrl))
            },
        )
    }
}
