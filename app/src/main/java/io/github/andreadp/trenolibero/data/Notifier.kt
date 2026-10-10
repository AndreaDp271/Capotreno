package io.github.andreadp.trenolibero.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.andreadp.trenolibero.MainActivity
import io.github.andreadp.trenolibero.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Impostazioni delle notifiche (tutte disattivate finché l'utente non le accende). */
class NotifySettings(context: Context) {
    private val p = context.getSharedPreferences("notify", Context.MODE_PRIVATE)

    var favorites: Boolean
        get() = p.getBoolean("favorites", false)
        set(v) = p.edit().putBoolean("favorites", v).apply()
    var tickets: Boolean
        get() = p.getBoolean("tickets", false)
        set(v) = p.edit().putBoolean("tickets", v).apply()
    var strikes: Boolean
        get() = p.getBoolean("strikes", false)
        set(v) = p.edit().putBoolean("strikes", v).apply()
    var subscriptions: Boolean
        get() = p.getBoolean("subscriptions", false)
        set(v) = p.edit().putBoolean("subscriptions", v).apply()
    /** Ritardo minimo (minuti) per avvisare. */
    var delayThreshold: Int
        get() = p.getInt("delayThreshold", 5)
        set(v) = p.edit().putInt("delayThreshold", v).apply()
    /** Minuti prima della partenza per il promemoria del biglietto. */
    var reminderMinutes: Int
        get() = p.getInt("reminderMinutes", 30)
        set(v) = p.edit().putInt("reminderMinutes", v).apply()

    val anyEnabled get() = favorites || tickets || strikes || subscriptions
}

/**
 * Notifiche locali: un controllo periodico (WorkManager, ~15 minuti) confronta lo stato attuale
 * con quello già notificato. Nessun servizio push esterno.
 */
object Notifier {
    const val CH_TRAINS = "treni"
    const val CH_TICKETS = "biglietti"
    const val CH_STRIKES = "scioperi"
    const val CH_SUBS = "abbonamenti"
    private const val WORK = "monitor"

    const val EXTRA_TRAIN = "open_train"       // "numero|origine|dataMs"
    const val EXTRA_SUBSCRIPTION = "open_subscription"
    const val EXTRA_INFO = "open_info"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CH_TRAINS, "Treni preferiti", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Ritardi, cambi di binario e soppressioni dei treni preferiti" },
            NotificationChannel(CH_TICKETS, "Biglietti", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Promemoria di partenza e variazioni del treno del biglietto" },
            NotificationChannel(CH_STRIKES, "Scioperi", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Nuovi scioperi annunciati" },
            NotificationChannel(CH_SUBS, "Abbonamenti", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Scadenza degli abbonamenti" },
        ).forEach { nm.createNotificationChannel(it) }
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Avvia o ferma il controllo periodico in base alle impostazioni. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!NotifySettings(ctx).anyEnabled) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun notify(ctx: Context, channel: String, id: Int, title: String, text: String, extra: Pair<String, String>? = null) {
        if (!canNotify(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            extra?.let { putExtra(it.first, it.second) }
        }
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_tab_train)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(if (channel == CH_TRAINS || channel == CH_TICKETS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}

/** Il controllo periodico. */
class MonitorWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val state = ctx.getSharedPreferences("notify_state", Context.MODE_PRIVATE)
    private val settings = NotifySettings(ctx)

    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        Notifier.createChannels(applicationContext)
        if (settings.tickets) runCatching { checkTickets() }
        if (settings.favorites) runCatching { checkFavorites() }
        if (settings.strikes) runCatching { checkStrikes() }
        if (settings.subscriptions) runCatching { checkSubscriptions() }
        return Result.success()
    }

    private fun once(key: String): Boolean {
        if (state.getBoolean(key, false)) return false
        state.edit().putBoolean(key, true).apply()
        return true
    }

    private fun hm(ms: Long?) = ms?.let {
        SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = ViaggiaTrenoApi.ROME }.format(Date(it))
    } ?: "--:--"

    /** Variazioni di un treno in corsa: ritardo oltre soglia (e ogni +5 min), cambio binario, soppressione. */
    private fun trainChanges(
        key: String, a: Andamento, platformStop: Fermata?, channel: String, extra: Pair<String, String>, prefix: String,
    ) {
        val id = key.hashCode()
        val route = "${a.origine.orEmpty()} → ${a.destinazione.orEmpty()}"
        if (a.cancelled && once("$key:cancel")) {
            Notifier.notify(applicationContext, channel, id, "$prefix${a.title} soppresso", route, extra); return
        }
        val delay = a.ritardo ?: 0
        val last = state.getInt("$key:delay", Int.MIN_VALUE)
        if (a.nonPartito != true && delay >= settings.delayThreshold && (last == Int.MIN_VALUE || delay - last >= 5)) {
            state.edit().putInt("$key:delay", delay).apply()
            val where = a.stazioneUltimoRilevamento?.takeIf { it.isNotBlank() && it != "--" }
                ?.let { "\nUltimo rilevamento: $it alle ${hm(a.oraUltimoRilevamento)}" }.orEmpty()
            Notifier.notify(applicationContext, channel, id, "$prefix${a.title}: +$delay min", route + where, extra)
        }
        val prog = platformStop?.binarioProgrammatoPartenzaDescrizione?.trim().orEmpty()
        val eff = platformStop?.binarioEffettivoPartenzaDescrizione?.trim().orEmpty()
        if (eff.isNotEmpty() && prog.isNotEmpty() && eff != prog && once("$key:platform:$eff")) {
            Notifier.notify(
                applicationContext, channel, id + 1, "$prefix${a.title}: cambio binario",
                "A ${platformStop?.stazione.orEmpty()} parte dal binario $eff (era $prog)", extra,
            )
        }
    }

    private suspend fun checkFavorites() {
        val now = System.currentTimeMillis()
        for (fav in Graph.favorites.trains.value) {
            val (m, a) = runCatching { resolveTrainData(fav.number, fav.originId, null) }.getOrNull() ?: continue
            val dep = a.orarioPartenza ?: continue
            val arr = a.orarioArrivo ?: dep
            if (now < dep - 60 * 60_000L || now > arr + 15 * 60_000L) continue
            val key = "fav:${m.number}:${m.dateMs}"
            trainChanges(key, a, a.fermate.firstOrNull(), Notifier.CH_TRAINS,
                Notifier.EXTRA_TRAIN to "${m.number}|${m.originId}|${m.dateMs}", "")
        }
    }

    private suspend fun checkTickets() {
        val now = System.currentTimeMillis()
        val today = SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).apply { timeZone = ViaggiaTrenoApi.ROME }.format(Date(now))
        for (t in Graph.tickets.tickets.value) {
            if (t.date != today || t.trains.isEmpty()) continue
            val train = t.trains.first()
            val (m, a) = runCatching { resolveTrainData(train.number, null, t.dateMs) }.getOrNull() ?: continue
            val stop = a.fermate.firstOrNull { DetectionLocator.normalize(it.stazione) == DetectionLocator.normalize(t.from) }
                ?: a.fermate.firstOrNull()
            val dep = stop?.partenzaTeorica ?: a.orarioPartenza ?: continue
            val arr = a.orarioArrivo ?: dep
            val extra = Notifier.EXTRA_TRAIN to "${m.number}|${m.originId}|${m.dateMs}"
            val minutes = (dep - now) / 60_000
            if (minutes in 0..settings.reminderMinutes.toLong() && once("ticket:${t.id}:reminder")) {
                val platform = (stop?.binarioEffettivoPartenzaDescrizione ?: stop?.binarioProgrammatoPartenzaDescrizione)
                    ?.takeIf { it.isNotBlank() }?.let { " · binario $it" }.orEmpty()
                val delay = a.ritardo?.takeIf { it != 0 }?.let { " · ${if (it > 0) "+" else ""}$it min" }.orEmpty()
                val seat = listOfNotNull(
                    t.coach.takeIf { it.isNotEmpty() }?.let { "carrozza $it" },
                    t.seat.takeIf { it.isNotEmpty() }?.let { "posto $it" },
                ).joinToString(", ").let { if (it.isEmpty()) "" else "\n${it.replaceFirstChar { c -> c.uppercase() }}" }
                Notifier.notify(
                    applicationContext, Notifier.CH_TICKETS, "ticket:${t.id}".hashCode(),
                    "Tra $minutes min parte il tuo ${a.title}",
                    "Alle ${hm(dep)} da ${stop?.stazione ?: t.from}$platform$delay$seat", extra,
                )
            }
            if (now in (dep - 60 * 60_000L)..(arr + 15 * 60_000L)) {
                trainChanges("ticket:${t.id}", a, stop, Notifier.CH_TICKETS, extra, "Biglietto: ")
            }
        }
    }

    private suspend fun checkStrikes() {
        val news = ViaggiaTrenoApi.infomobility(works = false) + runCatching { ViaggiaTrenoApi.infomobility(works = true) }.getOrDefault(emptyList())
        news.filter { categorize(it, false) == InfoCategory.STRIKE }.take(3).forEach { n ->
            if (once("strike:${n.title.hashCode()}")) {
                Notifier.notify(applicationContext, Notifier.CH_STRIKES, n.title.hashCode(), "Sciopero", n.title, Notifier.EXTRA_INFO to "1")
            }
        }
    }

    private fun checkSubscriptions() {
        for (s in Graph.subscriptions.items.value) {
            val days = s.daysLeft() ?: continue
            if (days !in listOf(7, 3, 1, 0)) continue
            if (once("sub:${s.id}:${s.validTo}:$days")) {
                Notifier.notify(
                    applicationContext, Notifier.CH_SUBS, "sub:${s.id}".hashCode(),
                    if (days == 0) "L'abbonamento scade oggi" else "L'abbonamento scade tra $days ${if (days == 1) "giorno" else "giorni"}",
                    "${s.name.ifEmpty { "${s.fromName} – ${s.toName}" }} · valido fino al ${s.validTo}",
                    Notifier.EXTRA_SUBSCRIPTION to s.id,
                )
            }
        }
    }
}

/** Come resolveTrain dell'interfaccia, ma utilizzabile dal controllo in background. */
suspend fun resolveTrainData(number: String, originId: String?, dateMs: Long?): Pair<TrainMatch, Andamento> {
    val match = if (originId != null && dateMs != null) {
        TrainMatch("", number, originId, dateMs)
    } else {
        val found = ViaggiaTrenoApi.searchTrain(number)
        val m = found.firstOrNull { it.originId == originId } ?: found.firstOrNull()
            ?: throw ApiException("Treno $number non trovato")
        if (dateMs != null) m.copy(dateMs = dateMs) else m
    }
    val a = ViaggiaTrenoApi.trainStatus(match.originId, match.number, match.dateMs) ?: throw ApiException("Dati non disponibili")
    return match to a
}
