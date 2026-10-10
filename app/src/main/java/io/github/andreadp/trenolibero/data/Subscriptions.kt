package io.github.andreadp.trenolibero.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Tipo di abbonamento e treni su cui vale. */
enum class SubscriptionType(val label: String, val kinds: Set<TrainKind>) {
    REGIONAL("Regionale", setOf(TrainKind.REGIONAL)),
    INTERCITY("Intercity (vale anche sui regionali)", setOf(TrainKind.INTERCITY, TrainKind.REGIONAL)),
    HIGH_SPEED("Frecce / AV", setOf(TrainKind.HIGH_SPEED)),
}

/** Operatore che emette l'abbonamento: limita le imprese su cui è valido. */
enum class SubscriptionOperator(val label: String) {
    ANY("Qualsiasi"),
    TRENITALIA("Trenitalia (anche regionali TPER)"),
    TRENORD("Trenord"),
    TPER("TPER");

    fun accepts(company: String): Boolean = when (this) {
        ANY -> true
        TRENITALIA -> company.startsWith("Trenitalia") || company == "Regionale"
        TRENORD -> company == "Trenord"
        TPER -> company == "Trenitalia TPER"
    }
}

@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val fromId: String,
    val fromName: String,
    val toId: String,
    val toName: String,
    val type: SubscriptionType = SubscriptionType.REGIONAL,
    val operator: SubscriptionOperator = SubscriptionOperator.ANY,
    /** "dd/MM/yyyy" */
    val validFrom: String = "",
    val validTo: String = "",
    val holder: String = "",
    /** Biglietto importato (PDF, immagine o scansione) con il codice da mostrare al controllore. */
    val ticketId: String? = null,
) {
    private fun parse(s: String): Long? = runCatching {
        SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).apply { timeZone = ViaggiaTrenoApi.ROME; isLenient = false }.parse(s)?.time
    }.getOrNull()

    val fromMs get() = parse(validFrom)
    /** Fine validità: fino alla fine del giorno indicato. */
    val toMs get() = parse(validTo)?.plus(24 * 3600 * 1000L - 1)

    /** Giorni di validità rimanenti (negativo = scaduto, null = date non indicate). */
    fun daysLeft(now: Long = System.currentTimeMillis()): Int? = toMs?.let { ((it - now) / (24 * 3600 * 1000L)).toInt() + if (it >= now) 0 else -1 }

    fun isActive(now: Long = System.currentTimeMillis()): Boolean =
        (fromMs ?: Long.MIN_VALUE) <= now && now <= (toMs ?: Long.MAX_VALUE)

    fun accepts(j: Journey): Boolean =
        j.legs.all { it.kind in type.kinds && operator.accepts(it.company) }

    companion object {
        fun formatDate(ms: Long): String =
            SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).apply { timeZone = ViaggiaTrenoApi.ROME }.format(Date(ms))

        /** Fine di un abbonamento mensile o annuale che inizia in [startMs]. */
        fun endAfter(startMs: Long, months: Int): Long = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply {
            timeInMillis = startMs
            add(Calendar.MONTH, months)
            add(Calendar.DAY_OF_MONTH, -1)
        }.timeInMillis

        fun newId() = UUID.randomUUID().toString()
    }
}

/** Abbonamenti salvati sul dispositivo. */
class SubscriptionStore(context: Context) {
    private val file = File(context.filesDir, "subscriptions.json")
    private val ser = ListSerializer(Subscription.serializer())
    private val _items = MutableStateFlow(read())
    val items: StateFlow<List<Subscription>> = _items

    private fun read(): List<Subscription> =
        runCatching { ViaggiaTrenoApi.json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())

    private fun write(list: List<Subscription>) {
        val sorted = list.sortedWith(compareByDescending<Subscription> { it.isActive() }.thenBy { it.toMs ?: Long.MAX_VALUE })
        file.writeText(ViaggiaTrenoApi.json.encodeToString(ser, sorted))
        _items.value = sorted
    }

    fun get(id: String) = _items.value.firstOrNull { it.id == id }
    fun save(s: Subscription) = write(_items.value.filterNot { it.id == s.id } + s)
    fun delete(id: String) = write(_items.value.filterNot { it.id == id })
}

/** Treni diretti tra le stazioni dell'abbonamento: compatibili e non (per segnalare un tipo impostato male). */
class CompatibleTrains(val compatible: List<Journey>, val others: List<Journey>)

/** Treni diretti compatibili con un abbonamento, nel verso indicato. */
suspend fun compatibleTrains(sub: Subscription, reverse: Boolean, time: Date): CompatibleTrains {
    val a = StationRef(sub.fromId, sub.fromName)
    val b = StationRef(sub.toId, sub.toName)
    val (from, to) = if (reverse) b to a else a to b
    val all = JourneySearch.direct(from, to, time).sortedBy { it.departure }
    val (ok, other) = all.partition { sub.accepts(it) }
    return CompatibleTrains(ok, other)
}
