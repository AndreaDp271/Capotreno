package io.github.andreadp.trenolibero.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ApiException(message: String) : IOException(message)

/**
 * Client dell'API REST pubblica (non documentata) usata da viaggiatreno.it/infomobilita.
 * Il CDN rifiuta i client senza User-Agent da browser, e risponde 406 se si chiede application/json.
 */
object ViaggiaTrenoApi {
    private const val BASE = "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    val ROME: TimeZone = TimeZone.getTimeZone("Europe/Rome")

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "it-IT,it;q=0.9")
                    .build()
            )
        }
        .build()

    private fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/$path").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException("Errore del server (${resp.code})")
            if (body.contains("Access Denied")) throw ApiException("Accesso negato dal server ViaggiaTreno")
            body
        }
    }

    // ---- Treni ----

    suspend fun searchTrain(number: String): List<TrainMatch> =
        get("cercaNumeroTrenoTrenoAutocomplete/${seg(number.trim())}")
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split("|")
                if (parts.size != 2) return@mapNotNull null
                val key = parts[1].split("-")
                if (key.size < 3) return@mapNotNull null
                TrainMatch(parts[0].trim(), key[0], key[1], key[2].toLongOrNull() ?: return@mapNotNull null)
            }
            .toList()

    /** Restituisce null se il treno non è (più) disponibile per quella data. */
    suspend fun trainStatus(originId: String, number: String, dateMs: Long): Andamento? {
        val body = get("andamentoTreno/${seg(originId)}/${seg(number)}/$dateMs").trim()
        if (body.isEmpty()) return null
        return json.decodeFromString(Andamento.serializer(), body)
    }

    // ---- Stazioni ----

    suspend fun searchStation(query: String): List<StationRef> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return get("autocompletaStazione/${seg(q.uppercase(Locale.ITALIAN))}")
            .lineSequence()
            .mapNotNull { line ->
                val p = line.trim().split("|")
                if (p.size == 2 && p[1].isNotBlank()) StationRef(p[1].trim(), p[0].trim()) else null
            }
            .toList()
    }

    private fun boardDate(time: Date): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US)
        f.timeZone = ROME
        return seg(f.format(time))
    }

    suspend fun departures(stationId: String, time: Date = Date()): List<BoardEntry> =
        parseList(get("partenze/${seg(stationId)}/${boardDate(time)}"))

    suspend fun arrivals(stationId: String, time: Date = Date()): List<BoardEntry> =
        parseList(get("arrivi/${seg(stationId)}/${boardDate(time)}"))

    private fun parseList(body: String): List<BoardEntry> {
        val b = body.trim()
        if (b.isEmpty()) return emptyList()
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BoardEntry.serializer()), b)
    }

    suspend fun regionOf(stationId: String): Int =
        get("regione/${seg(stationId)}").trim().toIntOrNull() ?: throw ApiException("Regione sconosciuta")

    suspend fun stationDetail(stationId: String): StationGeo {
        val reg = regionOf(stationId)
        return json.decodeFromString(StationGeo.serializer(), get("dettaglioStazione/${seg(stationId)}/$reg"))
    }

    suspend fun stationsOfRegion(region: Int): List<StationGeo> {
        val b = get("elencoStazioni/$region").trim()
        if (b.isEmpty()) return emptyList()
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(StationGeo.serializer()), b)
    }

    // ---- Linee (tratte) ----

    private const val CATEGORIE = "ES*,IC,EXP,EC,EN,REG,MET"

    /** Regione 0 = rete nazionale principale, 1..22 = tratte regionali di dettaglio. */
    suspend fun lines(region: Int): List<Tratta> {
        val b = get("elencoTratte/$region/8/${seg(CATEGORIE)}/null/${System.currentTimeMillis()}").trim()
        if (b.isEmpty()) return emptyList()
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Tratta.serializer()), b)
            .filter { it.latitudineA != 0.0 && it.latitudineB != 0.0 }
    }

    suspend fun lineTrains(region: Int, ab: Int, ba: Int): List<DirezioneTratta> {
        val b = get("dettagliTratta/$region/$ab/$ba/${seg(CATEGORIE)}/null").trim()
        if (b.isEmpty()) return emptyList()
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(DirezioneTratta.serializer()), b)
    }

    // ---- Infomobilità ----

    suspend fun statistics(): Statistiche =
        json.decodeFromString(Statistiche.serializer(), get("statistiche/${System.currentTimeMillis()}"))

    suspend fun ticker(): List<String> {
        val html = get("infomobilitaTicker/")
        return Regex("<li>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { stripTags(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
    }

    /** [works] = true: avvisi "infolavori" per regione (lavori programmati). */
    suspend fun infomobility(works: Boolean = false): List<InfoNews> {
        val html = get("infomobilitaRSS/$works")
        val titleRx = Regex("<a[^>]*class=\"([^\"]*)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val dateRx = Regex("<h4>(.*?)</h4>", RegexOption.DOT_MATCHES_ALL)
        val bodyRx = Regex("<div class=\"info-text[^\"]*\">(.*)", RegexOption.DOT_MATCHES_ALL)
        return html.split("<li class=\"editModeCollapsibleElement\">").drop(1).mapNotNull { chunk ->
            val t = titleRx.find(chunk) ?: return@mapNotNull null
            val body = bodyRx.find(chunk)?.groupValues?.get(1)
                ?.replace(Regex("(</div>\\s*)+(</li>)?\\s*(</ul>)?\\s*$"), "")
                .orEmpty()
            InfoNews(
                title = stripTags(t.groupValues[2]),
                date = dateRx.find(chunk)?.groupValues?.get(1)?.trim().orEmpty(),
                html = body,
                highlighted = t.groupValues[1].contains("inEvidenza"),
            )
        }
    }

    private fun stripTags(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
}
