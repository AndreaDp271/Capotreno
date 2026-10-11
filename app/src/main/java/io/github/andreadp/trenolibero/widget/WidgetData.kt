package io.github.andreadp.trenolibero.widget

import android.content.Context
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.data.InfoCategory
import io.github.andreadp.trenolibero.data.Ticket
import io.github.andreadp.trenolibero.data.ViaggiaTrenoApi
import io.github.andreadp.trenolibero.data.categorize
import io.github.andreadp.trenolibero.data.compatibleTrains
import io.github.andreadp.trenolibero.data.platformLabel
import io.github.andreadp.trenolibero.data.resolveTrainData
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------- Scelte e copia locale per ogni widget ----------------

/** Cosa mostra ogni widget (scelto nella configurazione) e l'ultima copia dei dati, per quando manca la rete. */
object WidgetPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun selection(ctx: Context, id: Int): String? = p(ctx).getString("sel_$id", null)
    fun setSelection(ctx: Context, id: Int, value: String) = p(ctx).edit().putString("sel_$id", value).apply()

    /** Abbonamento: direzione invertita a mano dal widget (null = automatica secondo l'ora). */
    fun reversed(ctx: Context, id: Int): Boolean? =
        if (p(ctx).contains("rev_$id")) p(ctx).getBoolean("rev_$id", false) else null
    fun setReversed(ctx: Context, id: Int, v: Boolean) = p(ctx).edit().putBoolean("rev_$id", v).apply()

    fun cache(ctx: Context, id: Int): String? = p(ctx).getString("cache_$id", null)
    fun setCache(ctx: Context, id: Int, json: String) = p(ctx).edit().putString("cache_$id", json).apply()

    fun remove(ctx: Context, id: Int) =
        p(ctx).edit().remove("sel_$id").remove("rev_$id").remove("cache_$id").apply()
}

/** Esito del caricamento: dati freschi, dati vecchi (rete assente) o niente da mostrare. */
data class WidgetState<T>(val data: T?, val stale: Boolean, val message: String? = null)

/** Carica i dati (con un secondo tentativo se la rete fallisce) e salva la copia locale;
 *  se la rete non risponde usa l'ultima copia, purché recente. */
internal suspend inline fun <reified T> loadCached(ctx: Context, id: Int, crossinline fetch: suspend () -> T): WidgetState<T> {
    var error: Exception? = null
    repeat(2) { attempt ->
        try {
            val d = fetch()
            WidgetPrefs.setCache(ctx, id, ViaggiaTrenoApi.json.encodeToString(d))
            return WidgetState(d, stale = false)
        } catch (e: Exception) {
            error = e
            if (attempt == 0) kotlinx.coroutines.delay(2_000)
        }
    }
    // la copia locale vale solo per poche ore: un treno di ieri non va mostrato come attuale
    val cached = WidgetPrefs.cache(ctx, id)?.let { runCatching { ViaggiaTrenoApi.json.decodeFromString<T>(it) }.getOrNull() }
        ?.takeIf { cachedAge(it) < 6 * 3600_000L }
    val msg = when (error) {
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException -> "Nessuna connessione: tocca ↻ per riprovare"
        else -> error?.message?.takeIf { it.isNotBlank() } ?: "Dati non disponibili: tocca ↻ per riprovare"
    }
    return WidgetState(cached, stale = true, message = if (cached == null) msg else null)
}

/** Età della copia locale (ms), dal campo updatedAt dei modelli. */
fun cachedAge(d: Any?): Long {
    val at = when (d) {
        is TrainW -> d.updatedAt
        is DeparturesW -> d.updatedAt
        is SubscriptionW -> d.updatedAt
        is InfoW -> d.updatedAt
        else -> return 0
    }
    return System.currentTimeMillis() - at
}

private val hm get() = SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = ViaggiaTrenoApi.ROME }
fun hhmm(ms: Long?): String = ms?.let { hm.format(Date(it)) } ?: "--:--"

// ---------------- Treno ----------------

@Serializable
data class TrainW(
    val title: String,
    val route: String,
    val status: String,
    /** "running", "standing", "waiting", "arrived", "cancelled", "unavailable" */
    val phase: String,
    val delay: Int? = null,
    val nextStop: String? = null,
    val nextTime: String? = null,
    val platform: String? = null,
    val detection: String? = null,
    val progress: Float = 0f,
    val number: String,
    val originId: String,
    val dateMs: Long,
    val updatedAt: Long,
)

/** Selezione "numero|origine|data|etichetta" (origine e data facoltative: si usa il treno di oggi). */
suspend fun loadTrain(selection: String): TrainW {
    val (num, origin, date, label) = (selection.split("|", limit = 4) + listOf("", "", "")).take(4)
    val dateMs = date.toLongOrNull()
    fun unavailable(why: String) = TrainW(
        title = label.ifBlank { "Treno $num" }.let { if (it.length > 40) it.take(40) + "…" else it },
        route = "", status = why, phase = "unavailable",
        number = num, originId = origin, dateMs = dateMs ?: 0, updatedAt = System.currentTimeMillis(),
    )
    // ViaggiaTreno conosce solo le corse di oggi: un treno che oggi non viaggia non compare
    val match = if (origin.isNotEmpty() && dateMs != null) {
        io.github.andreadp.trenolibero.data.TrainMatch("", num, origin, dateMs)
    } else {
        val found = ViaggiaTrenoApi.searchTrain(num)
        val m = found.firstOrNull { it.originId == origin } ?: found.firstOrNull()
            ?: return unavailable("Oggi il treno $num non circola")
        if (dateMs != null) m.copy(dateMs = dateMs) else m
    }
    val a = ViaggiaTrenoApi.trainStatus(match.originId, match.number, match.dateMs) ?: run {
        val today = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = SimpleDateFormat("dd/MM", Locale.ITALY).apply { timeZone = ViaggiaTrenoApi.ROME }.format(Date(match.dateMs))
        return unavailable(
            when {
                dateMs != null && dateMs > today -> "Viaggio del $day: i dati arrivano il giorno stesso"
                dateMs != null && dateMs < today -> "Viaggio del $day concluso"
                else -> "Oggi il treno $num non circola"
            },
        )
    }
    val m = match
    val stops = a.fermate
    val lastReached = stops.indexOfLast { it.reached && !it.suppressed }
    val next = if (a.cancelled || a.arrivato == true) -1
    else ((lastReached + 1)..stops.lastIndex).firstOrNull { !stops[it].suppressed } ?: -1
    val standing = a.nonPartito != true && a.arrivato != true && lastReached in 0 until stops.lastIndex &&
        stops[lastReached].let { it.arrivoReale != null && it.partenzaReale == null }
    val nextF = stops.getOrNull(next)
    val delayNow = nextF?.ritardo?.takeIf { it != 0 } ?: a.ritardo ?: 0
    val nextSched = nextF?.let { it.arrivoTeorico ?: it.partenzaTeorica ?: it.programmata }
    val phase = when {
        a.cancelled -> "cancelled"
        a.arrivato == true -> "arrived"
        a.nonPartito == true -> "waiting"
        standing -> "standing"
        else -> "running"
    }
    val status = when (phase) {
        "cancelled" -> "Treno soppresso"
        "arrived" -> "Arrivato a ${a.destinazione.orEmpty()}"
        "waiting" -> "Non ancora partito"
        "standing" -> "Fermo a ${stops[lastReached].stazione}"
        else -> "In viaggio verso ${nextF?.stazione.orEmpty()}"
    }
    // avanzamento: fermate superate più la frazione del tratto in corso (in base al tempo)
    val progress = if (stops.size < 2) 0f else when (phase) {
        "arrived" -> 1f
        "waiting", "cancelled" -> 0f
        else -> {
            val from = stops.getOrNull(lastReached)
            val start = from?.partenzaReale ?: from?.arrivoReale
            val end = nextSched?.plus(delayNow * 60_000L)
            val leg = if (start != null && end != null && end > start && phase == "running")
                ((System.currentTimeMillis() - start).toFloat() / (end - start)).coerceIn(0f, 0.95f) else 0f
            val span = (if (next >= 0) next else stops.lastIndex) - lastReached.coerceAtLeast(0)
            ((lastReached.coerceAtLeast(0) + leg * span) / stops.lastIndex).coerceIn(0f, 1f)
        }
    }
    val platform = nextF?.let {
        platformLabel(
            if (it.tipoFermata == "P") it.binarioEffettivoPartenzaDescrizione ?: it.binarioProgrammatoPartenzaDescrizione
            else it.binarioEffettivoArrivoDescrizione ?: it.binarioProgrammatoArrivoDescrizione
                ?: it.binarioEffettivoPartenzaDescrizione ?: it.binarioProgrammatoPartenzaDescrizione,
        )
    }
    val det = a.stazioneUltimoRilevamento?.takeIf { it.isNotBlank() && it != "--" && phase == "running" }
        ?.let { "Rilevato a $it alle ${hhmm(a.oraUltimoRilevamento)}" }
    return TrainW(
        title = a.title,
        route = "${a.origine.orEmpty()} → ${a.destinazione.orEmpty()}",
        status = status,
        phase = phase,
        delay = a.ritardo.takeIf { phase != "waiting" && phase != "cancelled" },
        nextStop = nextF?.stazione,
        nextTime = nextSched?.let { hhmm(it + delayNow * 60_000L) },
        platform = platform,
        detection = det,
        progress = progress,
        number = m.number, originId = m.originId, dateMs = m.dateMs,
        updatedAt = System.currentTimeMillis(),
    )
}

// ---------------- Partenze da una stazione ----------------

@Serializable
data class DepRow(
    val time: String,
    val title: String,
    val category: String,
    val destination: String,
    val delay: Int? = null,
    val platform: String? = null,
    val cancelled: Boolean = false,
    val departed: Boolean = false,
)

@Serializable
data class DeparturesW(val stationId: String, val stationName: String, val rows: List<DepRow>, val updatedAt: Long)

/** Selezione "id|nome". */
suspend fun loadDepartures(selection: String): DeparturesW {
    val (id, name) = (selection.split("|", limit = 2) + "").take(2)
    val rows = ViaggiaTrenoApi.departures(id).take(8).map { e ->
        DepRow(
            time = hhmm(e.orarioPartenza),
            title = e.title,
            category = e.title.trim().substringBefore(' '),
            destination = e.destinazione.orEmpty(),
            delay = e.ritardo.takeIf { e.nonPartito != true || (e.ritardo ?: 0) != 0 },
            platform = platformLabel(e.binarioEffettivoPartenzaDescrizione ?: e.binarioProgrammatoPartenzaDescrizione),
            cancelled = e.provvedimento == 1,
            departed = e.nonPartito == false && e.inStazione != true,
        )
    }
    return DeparturesW(id, name.ifEmpty { id }, rows, System.currentTimeMillis())
}

// ---------------- Abbonamento ----------------

@Serializable
data class SubRow(
    val time: String,
    val arrival: String,
    val title: String,
    val category: String,
    val delay: Int? = null,
    val platform: String? = null,
    val cancelled: Boolean = false,
    val number: String = "",
    val originId: String? = null,
    val dateMs: Long? = null,
)

@Serializable
data class SubscriptionW(
    val subId: String,
    val name: String,
    val from: String,
    val to: String,
    val reverse: Boolean,
    val rows: List<SubRow>,
    val updatedAt: Long,
)

/** Direzione automatica: andata fino a metà giornata o fino a metà tra gli orari abituali, poi ritorno. */
fun autoReverse(outbound: String, ret: String): Boolean {
    fun min(s: String) = s.split(":").takeIf { it.size == 2 }?.let { (h, m) -> (h.toIntOrNull() ?: return null) * 60 + (m.toIntOrNull() ?: 0) }
    val cal = Calendar.getInstance(ViaggiaTrenoApi.ROME)
    val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val o = min(outbound); val r = min(ret)
    val pivot = if (o != null && r != null && r > o) (o + r) / 2 else 13 * 60
    return now >= pivot
}

suspend fun loadSubscription(ctx: Context, widgetId: Int, subId: String): SubscriptionW {
    val s = Graph.subscriptions.items.value.firstOrNull { it.id == subId } ?: throw IllegalStateException("Abbonamento eliminato")
    val reverse = WidgetPrefs.reversed(ctx, widgetId) ?: autoReverse(s.outboundTime, s.returnTime)
    val trains = compatibleTrains(s, reverse, Date()).compatible
        .filter { it.departure > System.currentTimeMillis() - 10 * 60_000L || (it.delay ?: 0) > 0 }
        .take(6)
    return SubscriptionW(
        subId = s.id,
        name = s.name.ifEmpty { "Abbonamento" },
        from = if (reverse) s.toName else s.fromName,
        to = if (reverse) s.fromName else s.toName,
        reverse = reverse,
        rows = trains.map { j ->
            val l = j.legs.first()
            SubRow(
                time = hhmm(l.departure), arrival = hhmm(j.legs.last().arrival), title = l.title, category = l.category,
                delay = j.delay, platform = platformLabel(j.platform), cancelled = j.cancelled,
                number = l.number, originId = l.originId, dateMs = l.dateMs,
            )
        },
        updatedAt = System.currentTimeMillis(),
    )
}

// ---------------- Biglietto ----------------

/** Il prossimo viaggio (oggi o dopo); se non ce ne sono, l'abbonamento attivo con il codice. */
fun nextTicket(): Ticket? {
    val today = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val all = Graph.tickets.tickets.value
    all.filter { !it.isSubscription && (it.dateMs ?: -1) >= today }
        .sortedWith(compareBy({ it.dateMs }, { it.departure }))
        .firstOrNull()?.let { return it }
    val activeSubTickets = Graph.subscriptions.items.value.filter { it.isActive() }.mapNotNull { it.ticketId }
    return all.firstOrNull { it.id in activeSubTickets && it.codeText.isNotEmpty() }
        ?: all.firstOrNull { it.isSubscription && it.codeText.isNotEmpty() }
}

// ---------------- Infomobilità ----------------

@Serializable
data class InfoRow(val title: String, val category: String)

@Serializable
data class InfoW(val rows: List<InfoRow>, val strikes: Int, val updatedAt: Long)

suspend fun loadInfo(): InfoW {
    val news = ViaggiaTrenoApi.infomobility(works = false)
        .map { it to categorize(it, false) }
        .filter { (n, c) -> c != InfoCategory.OTHER && !n.title.lowercase().contains("circolazione regolare") }
    // scioperi per primi, poi i disagi di circolazione
    val sorted = news.sortedBy { (_, c) -> if (c == InfoCategory.STRIKE) 0 else if (c == InfoCategory.CIRCULATION) 1 else 2 }
    return InfoW(
        rows = sorted.take(6).map { (n, c) -> InfoRow(n.title, c.name) },
        strikes = news.count { it.second == InfoCategory.STRIKE },
        updatedAt = System.currentTimeMillis(),
    )
}
