package com.flexy.f1live.data

/**
 * Driver headshots by TLA (media.formula1.com URLs as served in the live feed's DriverList).
 * Used where a data source (Jolpica standings, ESPN) has no image: null when unknown.
 */
object DriverHeadshots {
    private const val BASE = "https://media.formula1.com/d_driver_fallback_image.png/content/dam/fom-website/drivers/"

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
        "HUL" to "N/NICHUL01_Nico_Hulkenberg/nichul01",
        "LAW" to "L/LIALAW01_Liam_Lawson/lialaw01",
        "LEC" to "C/CHALEC01_Charles_Leclerc/chalec01",
        "LIN" to "A/ARVLIN01_Arvid_Lindblad/arvlin01",
        "NOR" to "L/LANNOR01_Lando_Norris/lannor01",
        "OCO" to "E/ESTOCO01_Esteban_Ocon/estoco01",
        "PER" to "S/SERPER01_Sergio_Perez/serper01",
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

    fun forTla(tla: String?): String? =
        tla?.uppercase()?.let(byTla::get)?.let { "$BASE$it.png.transform/1col/image.png" }
}
