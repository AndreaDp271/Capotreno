package io.github.andreadp.trenolibero.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

/** Viaggio frequente: avvisa prima della partenza di ogni treno diretto da → a nella fascia oraria dei giorni scelti. */
@Serializable
data class FrequentTrip(
    val id: String,
    val fromId: String,
    val fromName: String,
    val toId: String,
    val toName: String,
    /** Giorni come Calendar.DAY_OF_WEEK (1 = domenica … 7 = sabato). */
    val days: Set<Int> = WEEKDAYS,
    val start: String = "07:00",
    val end: String = "09:00",
    val leadMinutes: Int = 15,
    val enabled: Boolean = true,
) {
    val title get() = "${TrainBanner.pretty(fromName)} → ${TrainBanner.pretty(toName)}"

    fun summary() = "${daysLabel(days)} · $start–$end · $leadMinutes min prima"

    companion object {
        val WEEKDAYS = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        /** Ordine di visualizzazione: da lunedì a domenica, con l'iniziale. */
        val WEEK = listOf(
            Calendar.MONDAY to "L", Calendar.TUESDAY to "M", Calendar.WEDNESDAY to "M", Calendar.THURSDAY to "G",
            Calendar.FRIDAY to "V", Calendar.SATURDAY to "S", Calendar.SUNDAY to "D",
        )
        private val SHORT = mapOf(
            Calendar.MONDAY to "lun", Calendar.TUESDAY to "mar", Calendar.WEDNESDAY to "mer", Calendar.THURSDAY to "gio",
            Calendar.FRIDAY to "ven", Calendar.SATURDAY to "sab", Calendar.SUNDAY to "dom",
        )

        fun daysLabel(days: Set<Int>): String = when (days) {
            WEEK.map { it.first }.toSet() -> "Tutti i giorni"
            WEEKDAYS -> "Lun–ven"
            WEEKDAYS + Calendar.SATURDAY -> "Lun–sab"
            setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> "Weekend"
            else -> WEEK.map { it.first }.filter { it in days }.joinToString(" ") { SHORT[it]!! }.replaceFirstChar { it.uppercase() }
        }

        fun minutes(hhmm: String): Int? = hhmm.split(":").takeIf { it.size == 2 }
            ?.let { (h, m) -> h.toIntOrNull()?.let { hh -> m.toIntOrNull()?.let { hh * 60 + it } } }
    }
}

/** Viaggi frequenti salvati sul dispositivo. */
object FrequentTrips {
    private val ser = ListSerializer(FrequentTrip.serializer())
    private fun p(ctx: Context) = ctx.getSharedPreferences("frequent", Context.MODE_PRIVATE)

    private val _items = MutableStateFlow<List<FrequentTrip>?>(null)
    /** Per l'interfaccia: si riempie alla prima lettura con [all]. */
    val items: StateFlow<List<FrequentTrip>?> get() = _items

    fun all(ctx: Context): List<FrequentTrip> =
        runCatching { ViaggiaTrenoApi.json.decodeFromString(ser, p(ctx).getString("list", "[]")!!) }.getOrDefault(emptyList())
            .also { _items.value = it }

    fun anyEnabled(ctx: Context) = all(ctx).any { it.enabled }

    private fun write(ctx: Context, list: List<FrequentTrip>) {
        p(ctx).edit().putString("list", ViaggiaTrenoApi.json.encodeToString(ser, list)).apply()
        _items.value = list
        Notifier.schedule(ctx)
    }

    fun save(ctx: Context, t: FrequentTrip) {
        val list = all(ctx)
        write(ctx, if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else list + t)
    }

    fun remove(ctx: Context, id: String) = write(ctx, all(ctx).filterNot { it.id == id })

    /**
     * Dal controllo periodico: per i viaggi di oggi, da un'ora prima della fascia (meno l'anticipo) fino alla sua fine,
     * cerca i treni diretti e programma un avviso per ciascuno. Si ricerca al massimo ogni 45 minuti per viaggio,
     * perché la ricerca legge l'andamento di molti treni.
     */
    suspend fun plan(ctx: Context, state: android.content.SharedPreferences) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance(ViaggiaTrenoApi.ROME)
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val midnight = now - nowMin * 60_000L - cal.get(Calendar.SECOND) * 1000L - cal.get(Calendar.MILLISECOND)
        for (t in all(ctx).filter { it.enabled && cal.get(Calendar.DAY_OF_WEEK) in it.days }) {
            val start = FrequentTrip.minutes(t.start) ?: continue
            val end = FrequentTrip.minutes(t.end)?.let { if (it < start) it + 24 * 60 else it } ?: continue
            if (nowMin < start - t.leadMinutes - 60 || nowMin > end) continue
            val lastKey = "freq:${t.id}:lastPlan"
            if (now - state.getLong(lastKey, 0) < 45 * 60_000L) continue
            state.edit().putLong(lastKey, now).apply()
            val from = midnight + start * 60_000L
            val to = midnight + end * 60_000L
            val found = JourneySearch.direct(StationRef(t.fromId, t.fromName), StationRef(t.toId, t.toName), Date(maxOf(now, from)))
            for (j in found.filter { it.departure in from..to }) {
                val l = j.legs.single()
                val origin = l.originId ?: continue
                val date = l.dateMs ?: continue
                val at = l.departure - t.leadMinutes * 60_000L
                if (at < now - 2 * 60_000L) continue
                val key = "freqtr:${t.id}:${l.number}:$date"
                if (state.getBoolean(key, false)) continue
                state.edit().putBoolean(key, true).apply()
                val req = OneTimeWorkRequestBuilder<FrequentTripWorker>()
                    .setInitialDelay((at - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .setInputData(
                        Data.Builder().putString("trip", t.id).putString("number", l.number)
                            .putString("origin", origin).putLong("date", date).build(),
                    )
                    .build()
                WorkManager.getInstance(ctx).enqueueUniqueWork(key, ExistingWorkPolicy.KEEP, req)
            }
        }
    }
}

/** L'avviso di un treno di un viaggio frequente, con i dati aggiornati al momento: binario, ritardo, soppressione. */
class FrequentTripWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val t = FrequentTrips.all(ctx).firstOrNull { it.id == inputData.getString("trip") && it.enabled } ?: return Result.success()
        val number = inputData.getString("number") ?: return Result.success()
        val origin = inputData.getString("origin") ?: return Result.success()
        val date = inputData.getLong("date", 0)
        val a = runCatching { ViaggiaTrenoApi.trainStatus(origin, number, date) }.getOrNull()
            ?: return if (runAttemptCount < 2) Result.retry() else Result.success()
        val stop = a.fermate.firstOrNull { it.id == t.fromId } ?: return Result.success()
        val extra = Notifier.EXTRA_TRAIN to "$number|$origin|$date"
        val id = "freq:${t.id}:$number:$date".hashCode()
        if (a.cancelled || stop.suppressed) {
            Notifier.notify(ctx, Notifier.CH_FREQUENT, id, "${a.title} soppresso",
                "${t.title}, partenza ${TrainBanner.hm(stop.partenzaTeorica ?: stop.programmata)}", extra,
                TrainAlerts.cancelled(a, stop.partenzaTeorica ?: stop.programmata))
            return Result.success()
        }
        val dep = TrainBanner.expected(stop, a, arrival = false)
        val minutes = dep?.let { ((it - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) } ?: 0
        Notifier.notify(
            ctx, Notifier.CH_FREQUENT, id,
            "Tra $minutes min parte il ${a.title}",
            "Alle ${TrainBanner.hm(dep)} da ${TrainBanner.pretty(stop.stazione)}", extra,
            TrainAlerts.reminder(a, stop, minutes, t.toName), follow = true,
        )
        return Result.success()
    }
}
