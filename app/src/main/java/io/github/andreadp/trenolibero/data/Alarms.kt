package io.github.andreadp.trenolibero.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.TimeUnit

/** "Sveglia" prima di una fermata del treno su cui si viaggia. */
@Serializable
data class StopAlarm(
    val number: String,
    val originId: String,
    val dateMs: Long,
    val stopId: String,
    val stopName: String,
    val minutesBefore: Int,
    val title: String,
) {
    val key get() = "$number/$originId/$dateMs/$stopId"
}

object StopAlarms {
    private val ser = ListSerializer(StopAlarm.serializer())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<StopAlarm> =
        runCatching { ViaggiaTrenoApi.json.decodeFromString(ser, prefs(ctx).getString("list", "[]")!!) }.getOrDefault(emptyList())

    private fun write(ctx: Context, list: List<StopAlarm>) =
        prefs(ctx).edit().putString("list", ViaggiaTrenoApi.json.encodeToString(ser, list)).apply()

    fun forTrain(ctx: Context, number: String, originId: String, dateMs: Long) =
        all(ctx).firstOrNull { it.number == number && it.originId == originId && it.dateMs == dateMs }

    /** Una sola sveglia per treno: la nuova sostituisce la precedente. */
    fun set(ctx: Context, a: StopAlarm) {
        all(ctx).filter { it.number == a.number && it.originId == a.originId && it.dateMs == a.dateMs }.forEach { cancel(ctx, it) }
        write(ctx, all(ctx) + a)
        Notifier.createChannels(ctx)
        schedule(ctx, a, 0)
    }

    fun cancel(ctx: Context, a: StopAlarm) {
        write(ctx, all(ctx).filterNot { it.key == a.key })
        WorkManager.getInstance(ctx).cancelUniqueWork("alarm:${a.key}")
    }

    fun schedule(ctx: Context, a: StopAlarm, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<AlarmWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString("key", a.key).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("alarm:${a.key}", ExistingWorkPolicy.REPLACE, req)
    }
}

/**
 * Controlla l'arrivo previsto alla fermata (orario + ritardo attuale): se manca più dell'anticipo scelto
 * si riprogramma (al massimo tra 20 minuti, per seguire le variazioni di ritardo), altrimenti avvisa.
 */
class AlarmWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val key = inputData.getString("key") ?: return Result.success()
        val alarm = StopAlarms.all(ctx).firstOrNull { it.key == key } ?: return Result.success()
        val now = System.currentTimeMillis()
        if (now > alarm.dateMs + 36 * 3600_000L) { StopAlarms.cancel(ctx, alarm); return Result.success() }
        val a = runCatching { ViaggiaTrenoApi.trainStatus(alarm.originId, alarm.number, alarm.dateMs) }.getOrNull()
        if (a == null) { StopAlarms.schedule(ctx, alarm, 5 * 60_000L); return Result.success() }
        val extra = Notifier.EXTRA_TRAIN to "${alarm.number}|${alarm.originId}|${alarm.dateMs}"
        val id = "alarm:$key".hashCode()
        val stop = a.fermate.firstOrNull { it.id == alarm.stopId }
        if (a.cancelled || stop == null || stop.suppressed) {
            Notifier.notify(ctx, Notifier.CH_ALARMS, id, "${a.title}: fermata non servita",
                "Il treno non fermerà a ${alarm.stopName}" + if (a.cancelled) " (treno soppresso)" else "", extra)
            StopAlarms.cancel(ctx, alarm); return Result.success()
        }
        val scheduled = stop.arrivoTeorico ?: stop.programmata ?: run { StopAlarms.cancel(ctx, alarm); return Result.success() }
        val delayMin = if (stop.arrivoReale != null) 0 else (stop.ritardo ?: a.ritardo ?: 0)
        val expected = stop.arrivoReale ?: (scheduled + delayMin * 60_000L)
        val remaining = expected - now
        val lead = alarm.minutesBefore * 60_000L
        if (stop.arrivoReale != null || remaining <= lead + 60_000L) {
            val minutes = (remaining / 60_000).coerceAtLeast(0)
            val platform = platformLabel(stop.binarioEffettivoArrivoDescrizione ?: stop.binarioProgrammatoArrivoDescrizione)
                ?.takeIf { it.isNotBlank() }?.let { " · binario $it" }.orEmpty()
            Notifier.notify(
                ctx, Notifier.CH_ALARMS, id,
                if (stop.arrivoReale != null) "Sei arrivato a ${alarm.stopName}" else "Tra $minutes min arrivi a ${alarm.stopName}",
                "${a.title} · arrivo previsto ${hhmm(expected)}$platform" + if (delayMin > 0) " (+$delayMin min)" else "",
                extra,
            )
            StopAlarms.cancel(ctx, alarm)
        } else {
            // ricontrollo: un po' prima dell'avviso, ma non oltre 20 minuti
            StopAlarms.schedule(ctx, alarm, minOf(remaining - lead - 60_000L, 20 * 60_000L))
        }
        return Result.success()
    }

    private fun hhmm(ms: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY)
        .apply { timeZone = ViaggiaTrenoApi.ROME }.format(java.util.Date(ms))
}
