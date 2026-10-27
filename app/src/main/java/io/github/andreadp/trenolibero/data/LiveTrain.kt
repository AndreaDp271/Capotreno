package io.github.andreadp.trenolibero.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.andreadp.trenolibero.R
import io.github.andreadp.trenolibero.widget.Widgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

/**
 * "Segui in notifica": una notifica fissa per ogni treno seguito (linea delle fermate, prossime fermate),
 * aggiornata ogni minuto circa finché il treno non arriva. Il widget "Treno seguito" mostra il primo.
 */
object LiveTrain {
    private const val WORK = "live"
    /** Selezione del widget Treno che mostra il treno seguito, qualunque sia. */
    const val WIDGET_SELECTION = "live|||Treno seguito"

    private fun p(ctx: Context) = ctx.getSharedPreferences("live", Context.MODE_PRIVATE)

    private fun notificationId(value: String) = "live:$value".hashCode()

    private val _followed = MutableStateFlow<Set<String>>(emptySet())
    /** Treni seguiti come "numero|origine|dataMs", per l'interfaccia. */
    val followedFlow: StateFlow<Set<String>> get() = _followed

    fun all(ctx: Context): List<String> {
        val prefs = p(ctx)
        // prima si seguiva un solo treno, in "train"
        prefs.getString("train", null)?.let { old ->
            prefs.edit().remove("train").putStringSet("trains", (prefs.getStringSet("trains", emptySet())!! + old)).apply()
        }
        return prefs.getStringSet("trains", emptySet())!!.sorted().also { _followed.value = it.toSet() }
    }

    /** Il primo treno seguito (per il widget). */
    fun current(ctx: Context): String? = all(ctx).firstOrNull()

    fun start(ctx: Context, value: String) {
        p(ctx).edit().putStringSet("trains", all(ctx).toSet() + value).apply()
        _followed.value = all(ctx).toSet()
        Notifier.createChannels(ctx)
        schedule(ctx, 0, ExistingWorkPolicy.REPLACE)
    }

    /** Smette di seguire [value]; con [keepNotification] lascia l'ultima notifica (arrivo, soppressione). */
    fun stop(ctx: Context, value: String, keepNotification: Boolean = false) {
        val left = all(ctx).toSet() - value
        p(ctx).edit().putStringSet("trains", left).apply()
        _followed.value = left
        if (left.isEmpty()) WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        if (!keepNotification) NotificationManagerCompat.from(ctx).cancel(notificationId(value))
        Widgets.refreshLiveSoon(ctx)
    }

    fun schedule(ctx: Context, delayMs: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val req = OneTimeWorkRequestBuilder<LiveTrainWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, policy, req)
    }

    fun followIntent(ctx: Context, value: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, value.hashCode(),
        Intent(ctx, LiveTrainReceiver::class.java).setAction(LiveTrainReceiver.FOLLOW).putExtra(Notifier.EXTRA_TRAIN, value),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(ctx: Context, value: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, notificationId(value),
        Intent(ctx, LiveTrainReceiver::class.java).setAction(LiveTrainReceiver.STOP).putExtra(Notifier.EXTRA_TRAIN, value),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** La notifica fissa; a viaggio finito diventa normale e sparisce da sola dopo un'ora. */
    fun post(ctx: Context, value: String, a: Andamento, ended: Boolean) {
        if (!Notifier.canNotify(ctx)) return
        val title = TrainBanner.title(a)
        val bg = when {
            a.cancelled -> R.drawable.notif_hero_red
            a.arrivato == true -> R.drawable.notif_hero_green
            else -> R.drawable.notif_hero_blue
        }
        val text = TrainBanner.status(a)
        val b = Notifier.builder(ctx, Notifier.CH_LIVE, notificationId(value), Notifier.EXTRA_TRAIN to value)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (ended) {
            b.setAutoCancel(true).setOngoing(false).setTimeoutAfter(60 * 60_000L)
        } else {
            b.setAutoCancel(false).setOngoing(true)
                .setDeleteIntent(stopIntent(ctx, value))       // da Android 14 le notifiche fisse si possono scorrere via
                .addAction(R.drawable.ic_tab_train, "Smetti di seguire", stopIntent(ctx, value))
        }
        val nm = NotificationManagerCompat.from(ctx)
        val rich = runCatching {
            b.setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(FollowCard.small(ctx, a, bg) ?: TrainBanner.small(ctx, title, text, a, bg))
                .setCustomBigContentView(FollowCard.big(ctx, a, bg, title) ?: TrainBanner.big(ctx, title, detail(a), a, bg))
            @Suppress("MissingPermission")
            nm.notify(notificationId(value), b.build())
        }
        if (rich.isFailure) {
            @Suppress("MissingPermission")
            nm.notify(notificationId(value), b.setStyle(NotificationCompat.BigTextStyle().bigText(detail(a))).build())
        }
    }

    /** Testo della notifica espansa: stato, ultimo rilevamento, motivo del ritardo. */
    private fun detail(a: Andamento): String = listOfNotNull(
        TrainBanner.status(a),
        a.stazioneUltimoRilevamento?.takeIf { it.isNotBlank() && it != "--" && a.arrivato != true && a.nonPartito != true }
            ?.let { "Rilevato a ${TrainBanner.pretty(it)} alle ${TrainBanner.hm(a.oraUltimoRilevamento)}" },
        a.motivoRitardoPrevalente?.takeIf { it.isNotBlank() },
    ).joinToString("\n")
}

/** Aggiorna le notifiche dei treni seguiti e si riprogramma: ogni minuto in viaggio, più di rado prima della partenza. */
class LiveTrainWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        var next: Long? = null
        for (value in LiveTrain.all(ctx)) {
            val delay = update(ctx, value) ?: continue
            next = minOf(next ?: delay, delay)
        }
        Widgets.refreshLive(ctx)
        next?.let { LiveTrain.schedule(ctx, it) }
        return Result.success()
    }

    /** Aggiorna un treno; restituisce fra quanto riaggiornarlo, o null se non lo si segue più. */
    private suspend fun update(ctx: Context, value: String): Long? {
        val (number, origin, date) = value.split("|").takeIf { it.size == 3 } ?: run { LiveTrain.stop(ctx, value); return null }
        val dateMs = date.toLongOrNull() ?: run { LiveTrain.stop(ctx, value); return null }
        val now = System.currentTimeMillis()
        if (now > dateMs + 36 * 3600_000L) { LiveTrain.stop(ctx, value); return null }
        val a = runCatching { ViaggiaTrenoApi.trainStatus(origin, number, dateMs) }.getOrNull()
        if (value !in LiveTrain.all(ctx)) return null      // smesso di seguire nel frattempo
        if (a == null) return 2 * 60_000L
        val ended = a.arrivato == true || a.cancelled
        LiveTrain.post(ctx, value, a, ended)
        if (ended) { LiveTrain.stop(ctx, value, keepNotification = true); return null }
        val untilDeparture = (a.orarioPartenza ?: now) - now
        return when {
            a.nonPartito == true && untilDeparture > 90 * 60_000L -> 15 * 60_000L
            a.nonPartito == true && untilDeparture > 20 * 60_000L -> 5 * 60_000L
            else -> 60_000L
        }
    }
}

/** Pulsanti "Segui" e "Smetti di seguire" delle notifiche. */
class LiveTrainReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            FOLLOW -> intent.getStringExtra(Notifier.EXTRA_TRAIN)?.let { LiveTrain.start(ctx, it) }
            STOP -> intent.getStringExtra(Notifier.EXTRA_TRAIN)?.let { LiveTrain.stop(ctx, it) }
        }
    }

    companion object {
        const val FOLLOW = "io.github.andreadp.trenolibero.FOLLOW_TRAIN"
        const val STOP = "io.github.andreadp.trenolibero.STOP_FOLLOWING"
    }
}
