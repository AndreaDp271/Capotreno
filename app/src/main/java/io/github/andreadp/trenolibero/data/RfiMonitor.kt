package io.github.andreadp.trenolibero.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Avvisi di stazione dai monitor pubblici RFI (iechub.rfi.it, "Arrivi e partenze"): la riga "Avvisi" in fondo
 * ai tabelloni. RFI non copre le stazioni di Grandi Stazioni (Milano Centrale, Roma Termini…): lì non c'è niente.
 */
object RfiMonitor {
    private const val BASE = "https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val OPTION = Regex("<option value=\"(\\d+)\">([^<]+)<")
    private val NOTICE_BOX = Regex("class=\"marqueeinfosupp\">(.*?)</div>\\s*</div>", RegexOption.DOT_MATCHES_ALL)
    private val INNER = Regex("<div[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("RFI: errore ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /** Nomi RFI e ViaggiaTreno confrontati senza punteggiatura e con le abbreviazioni sciolte ("BOLOGNA C.LE/AV" = "BOLOGNA CENTRALE"). */
    internal fun key(name: String): String = DetectionLocator.normalize(
        name.uppercase().replace("C.LE", "CENTRALE").replace("/AV", "").replace(Regex("[.'/-]"), " "),
    ).replace(Regex("\\s+"), " ").trim()

    private var places: Map<String, String>? = null

    /** Stazioni coperte dai monitor (nome → placeId), dalla pagina iniziale; in cache per 30 giorni. */
    private suspend fun places(ctx: Context): Map<String, String> = places ?: withContext(Dispatchers.IO) {
        val f = File(ctx.cacheDir, "rfi_places.txt")
        val text = if (f.isFile && System.currentTimeMillis() - f.lastModified() < 30L * 24 * 3600_000) f.readText() else {
            OPTION.findAll(get(BASE + "Home")).joinToString("\n") { "${it.groupValues[1]}|${it.groupValues[2].trim()}" }
                .also { if (it.isNotEmpty()) f.writeText(it) }
        }
        text.lines().mapNotNull { l -> l.split("|", limit = 2).takeIf { it.size == 2 }?.let { key(it[1]) to it[0] } }.toMap()
            .also { places = it }
    }

    /** Avvisi della stazione (testi distinti di partenze e arrivi); null se RFI non ha un monitor per questa stazione. */
    suspend fun notices(ctx: Context, stationName: String): List<String>? {
        val id = places(ctx)[key(stationName)] ?: return null
        return coroutineScope {
            listOf("False", "True").map { arrivals ->
                async(Dispatchers.IO) { runCatching { parse(get("${BASE}Monitor?placeId=$id&arrivals=$arrivals")) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten().distinct()
        }
    }

    private val STANDING = listOf(
        "VIETATO ATTRAVERSARE", "LINEA GIALLA", "SOTTOPASSAGGIO", "PORTE ESTERNE", "OLTREPASSARE", "ALLONTANARSI",
        "VIETATO FUMARE", "BAGAGLI INCUSTODITI", "VIDEOSORVEGLIA", "NON SOSTARE",
    )

    /** Avvisi fissi di sicurezza ("vietato attraversare i binari", "linea gialla"…): sempre uguali, non sono disagi. */
    fun isStanding(text: String): Boolean = text.uppercase().let { t -> STANDING.any { it in t } }

    internal fun parse(html: String): List<String> = NOTICE_BOX.findAll(html).flatMap { box ->
        INNER.findAll(box.groupValues[1]).map { it.groupValues[1] }.ifEmpty { sequenceOf(box.groupValues[1]) }
    }.map { android.text.Html.fromHtml(it, 0).toString().replace(Regex("\\s+"), " ").trim() }
        .filter { it.length > 3 }.toList()
}
