package io.github.andreadp.trenolibero.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Tipo di treno per i filtri. */
enum class TrainKind(val label: String) { HIGH_SPEED("Frecce e AV"), INTERCITY("Intercity / EC"), REGIONAL("Regionali") }

fun trainKind(category: String): TrainKind = when (category.trim().uppercase()) {
    "FR", "FA", "FB", "ITA", "AV", "ES", "ES*", "FRECCIAROSSA", "FRECCIARGENTO", "FRECCIABIANCA" -> TrainKind.HIGH_SPEED
    "IC", "ICN", "EC", "EN", "INTERCITY", "EUROCITY" -> TrainKind.INTERCITY
    else -> TrainKind.REGIONAL
}

/** Compagnia dal codiceCliente di ViaggiaTreno. */
fun companyName(code: Int?): String = when (code) {
    1, 2, 4 -> "Trenitalia"
    18 -> "Trenitalia TPER"
    63 -> "Trenord"
    64 -> "ÖBB"
    null -> "Trenitalia"
    else -> "Altro operatore ($code)"
}

data class JourneyLeg(
    val category: String,
    val number: String,
    val from: String,
    val to: String,
    val departure: Long,
    val arrival: Long,
    val company: String,
    val originId: String? = null,
    val dateMs: Long? = null,
) {
    val kind get() = trainKind(category)
    val title get() = "$category $number".trim()
}

data class Journey(
    val legs: List<JourneyLeg>,
    val price: Double? = null,
    /** Dati in tempo reale (solo per i diretti letti da ViaggiaTreno). */
    val delay: Int? = null,
    val platform: String? = null,
    val cancelled: Boolean = false,
) {
    val departure get() = legs.first().departure
    val arrival get() = legs.last().arrival
    val changes get() = legs.size - 1
    val companies get() = legs.map { it.company }.distinct()
    val kinds get() = legs.map { it.kind }.toSet()
}

/**
 * Ricerca viaggi da A a B:
 * - soluzioni Trenitalia (anche con cambi, con prezzo) dal servizio di vendita lefrecce.it;
 * - treni diretti di tutte le compagnie presenti su ViaggiaTreno (Trenord, TPER, ÖBB…), dalle partenze di A.
 * Italo non pubblica i propri treni su nessuno dei due servizi.
 */
object JourneySearch {
    private const val LEFRECCE = "https://www.lefrecce.it/Channels.Website.BFF.WEB/website/ticket/solutions"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** Nome della stazione come lo usa lefrecce (es. "VENEZIA S.LUCIA" -> "Venezia S. Lucia"). */
    private fun lefrecceName(name: String): String = runCatching {
        val url = "https://www.lefrecce.it/Channels.Website.BFF.WEB/website/locations/search?name=" +
            java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20") + "&limit=1"
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36").build()
        client.newCall(req).execute().use { r ->
            val arr = ViaggiaTrenoApi.json.parseToJsonElement(r.body!!.string()) as kotlinx.serialization.json.JsonArray
            (arr.firstOrNull() as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull() ?: name

    /**
     * Pagina di acquisto Trenitalia già compilata (tratta, data, ora e tipo di treno), tramite il punto
     * d'ingresso "handoff" che il sito offre ai siti esterni. La soluzione si sceglie dall'elenco, che parte
     * dall'ora del treno.
     */
    suspend fun trenitaliaBuyUrl(j: Journey): String = withContext(Dispatchers.IO) {
        val from = lefrecceName(j.legs.first().from)
        val to = lefrecceName(j.legs.last().to)
        val date = SimpleDateFormat("dd-MM-yyyy", Locale.US).apply { timeZone = ViaggiaTrenoApi.ROME }.format(Date(j.departure))
        val hour = SimpleDateFormat("HH", Locale.US).apply { timeZone = ViaggiaTrenoApi.ROME }.format(Date(j.departure))
        val type = when (j.kinds) {
            setOf(TrainKind.HIGH_SPEED) -> "frecce"
            setOf(TrainKind.REGIONAL) -> "regionali"
            setOf(TrainKind.INTERCITY) -> "intercity"
            else -> "tutti"
        }
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        "https://www.lefrecce.it/Channels.Website.WEB/#/handoff?departureStation=${enc(from)}&arrivalStation=${enc(to)}" +
            "&departureDate=$date&departureTime=$hour&noOfAdults=1&noOfChildren=0&selectedTrainType=$type&lang=it"
    }

    /**
     * Lefrecce vende anche regionali di altre imprese (es. Trenord) senza indicarle:
     * la compagnia si attribuisce solo dove è certa; i diretti letti da ViaggiaTreno la riportano esattamente.
     */
    private fun lefrecceCompany(acronym: String) = when (trainKind(acronym)) {
        TrainKind.HIGH_SPEED, TrainKind.INTERCITY -> "Trenitalia"
        TrainKind.REGIONAL -> if (acronym.uppercase() == "BU") "Bus" else "Regionale"
    }

    /** Il codice ViaggiaTreno S02581 corrisponde all'identificativo 830002581 di lefrecce. */
    private fun lefrecceId(vtId: String) = vtId.filter { it.isDigit() }.toLongOrNull()?.let { 830_000_000L + it }

    private fun parseIso(s: String?): Long? = s?.let {
        runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(it)?.time }.getOrNull()
    }

    suspend fun search(from: StationRef, to: StationRef, time: Date): List<Journey> = coroutineScope {
        val tren = async { runCatching { trenitalia(from, to, time) }.getOrDefault(emptyList()) }
        val direct = async { runCatching { directFromBoards(from, to, time) }.getOrDefault(emptyList()) }
        merge(tren.await(), direct.await())
    }

    private fun merge(trenitalia: List<Journey>, direct: List<Journey>): List<Journey> {
        val out = trenitalia.map { j ->
            // un diretto presente in entrambe le fonti: prezzo da Trenitalia, dati in tempo reale da ViaggiaTreno
            val live = if (j.legs.size == 1) direct.firstOrNull { it.legs.single().number == j.legs.single().number } else null
            if (live != null) j.copy(legs = live.legs, delay = live.delay, platform = live.platform) else j
        }.toMutableList()
        val numbers = out.filter { it.legs.size == 1 }.map { it.legs.single().number }.toSet()
        out += direct.filter { it.legs.single().number !in numbers }
        return out.sortedBy { it.departure }
    }

    private suspend fun trenitalia(from: StationRef, to: StationRef, time: Date): List<Journey> = withContext(Dispatchers.IO) {
        val a = lefrecceId(from.id) ?: return@withContext emptyList()
        val b = lefrecceId(to.id) ?: return@withContext emptyList()
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.000", Locale.US).apply { timeZone = ViaggiaTrenoApi.ROME }
        (0..1).flatMap { page ->
            val body = buildJsonObject {
                put("departureLocationId", a)
                put("arrivalLocationId", b)
                put("departureTime", f.format(time))
                put("adults", 1)
                put("children", 0)
                putJsonObject("criteria") {
                    put("frecceOnly", false); put("regionalOnly", false); put("noChanges", false)
                    put("order", "DEPARTURE_DATE"); put("offset", page * 10); put("limit", 10)
                }
                putJsonObject("advancedSearchRequest") { put("bestFare", false) }
            }.toString()
            val req = Request.Builder().url(LEFRECCE)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36")
                .header("Accept", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val text = client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return@flatMap emptyList()
            val root = ViaggiaTrenoApi.json.parseToJsonElement(text) as? JsonObject ?: return@flatMap emptyList()
            (root["solutions"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { el ->
                val sol = (el as? JsonObject)?.get("solution") as? JsonObject ?: return@mapNotNull null
                val legs = (sol["nodes"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { n ->
                    val o = n as? JsonObject ?: return@mapNotNull null
                    val train = o["train"] as? JsonObject
                    JourneyLeg(
                        category = train?.get("acronym")?.jsonPrimitive?.contentOrNull.orEmpty(),
                        number = train?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                        from = o["origin"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        to = o["destination"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        departure = parseIso(o["departureTime"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null,
                        arrival = parseIso(o["arrivalTime"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null,
                        company = lefrecceCompany(train?.get("acronym")?.jsonPrimitive?.contentOrNull.orEmpty()),
                    )
                }
                if (legs.isEmpty()) null
                else Journey(legs, price = (sol["price"] as? JsonObject)?.get("amount")?.jsonPrimitive?.doubleOrNull)
            }
        }.distinctBy { j -> j.legs.joinToString { it.number } + j.departure }
    }

    /** Treni diretti: partenze di A nelle prossime ~3 ore che fermano anche a B. */
    /** Solo treni diretti, in tempo reale, di tutte le imprese su ViaggiaTreno (usato anche per gli abbonamenti). */
    suspend fun direct(from: StationRef, to: StationRef, time: Date): List<Journey> = directFromBoards(from, to, time)

    private suspend fun directFromBoards(from: StationRef, to: StationRef, time: Date): List<Journey> = coroutineScope {
        val boards = (0..1).map { i ->
            async { runCatching { ViaggiaTrenoApi.departures(from.id, Date(time.time + i * 90 * 60_000L)) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten().distinctBy { "${it.numeroTreno}/${it.codOrigine}" }
            .filter { it.codOrigine != null && it.dataPartenzaTreno != null }
            .take(60)
        val sem = Semaphore(6)
        boards.map { e ->
            async {
                sem.withPermit {
                    runCatching {
                        val a = ViaggiaTrenoApi.trainStatus(e.codOrigine!!, e.numeroTreno.toString(), e.dataPartenzaTreno!!)
                            ?: return@runCatching null
                        val i = a.fermate.indexOfFirst { it.id == from.id }
                        val j = a.fermate.indexOfFirst { it.id == to.id }
                        if (i < 0 || j <= i || a.fermate[j].suppressed) return@runCatching null
                        val fa = a.fermate[i]; val fb = a.fermate[j]
                        val leg = JourneyLeg(
                            category = (e.categoriaDescrizione ?: a.categoria ?: "").trim().ifEmpty { e.title.substringBefore(' ') },
                            number = e.numeroTreno.toString(),
                            from = fa.stazione, to = fb.stazione,
                            departure = fa.partenzaTeorica ?: fa.programmata ?: return@runCatching null,
                            arrival = fb.arrivoTeorico ?: fb.programmata ?: return@runCatching null,
                            company = companyName(e.codiceCliente),
                            originId = e.codOrigine, dateMs = e.dataPartenzaTreno,
                        )
                        Journey(
                            listOf(leg),
                            delay = a.ritardo.takeIf { a.nonPartito != true },
                            platform = platformLabel(fa.binarioEffettivoPartenzaDescrizione ?: fa.binarioProgrammatoPartenzaDescrizione),
                            cancelled = e.provvedimento == 1 || a.cancelled,
                        )
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }
}
