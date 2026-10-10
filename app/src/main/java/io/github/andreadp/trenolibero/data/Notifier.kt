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

    /** Riepilogo partenze a orario fisso. */
    var digest: Boolean
        get() = p.getBoolean("digest", false)
        set(v) = p.edit().putBoolean("digest", v).apply()
    var digestStation: String          // "id|nome"
        get() = p.getString("digestStation", "")!!
        set(v) = p.edit().putString("digestStation", v).apply()
    var digestDestination: String      // "id|nome" o vuoto
        get() = p.getString("digestDestination", "")!!
        set(v) = p.edit().putString("digestDestination", v).apply()
    var digestTime: String             // "HH:mm"
        get() = p.getString("digestTime", "07:30")!!
        set(v) = p.edit().putString("digestTime", v).apply()
    var digestWeekdays: Boolean
        get() = p.getBoolean("digestWeekdays", true)
        set(v) = p.edit().putBoolean("digestWeekdays", v).apply()
    /** Disagi di circolazione sulle località di abbonamenti e preferiti. */
    var disruptions: Boolean
        get() = p.getBoolean("disruptions", false)
        set(v) = p.edit().putBoolean("disruptions", v).apply()
    /** Ritardi/soppressioni dei treni compatibili con l'abbonamento negli orari abituali. */
    var subscriptionTrains: Boolean
        get() = p.getBoolean("subscriptionTrains", false)
        set(v) = p.edit().putBoolean("subscriptionTrains", v).apply()

    val anyEnabled get() = favorites || tickets || strikes || subscriptions || digest || disruptions || subscriptionTrains
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
    const val CH_ALARMS = "sveglia"
    const val CH_DIGEST = "riepilogo"
    const val CH_DISRUPTIONS = "disagi"
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
                .apply { description = "Scadenza degli abbonamenti e treni negli orari abituali" },
            NotificationChannel(CH_ALARMS, "Sveglia fermata", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Avviso prima di arrivare alla tua fermata" },
            NotificationChannel(CH_DIGEST, "Riepilogo partenze", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Prossimi treni dalla stazione scelta, a orario fisso" },
            NotificationChannel(CH_DISRUPTIONS, "Disagi sulle tue linee", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Guasti e rallentamenti nelle località dei tuoi viaggi" },
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
        if (settings.digest) runCatching { checkDigest() }
        if (settings.disruptions) runCatching { checkDisruptions() }
        if (settings.subscriptionTrains) runCatching { checkSubscriptionTrains() }
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
        val prog = platformLabel(platformStop?.binarioProgrammatoPartenzaDescrizione).orEmpty()
        val eff = platformLabel(platformStop?.binarioEffettivoPartenzaDescrizione).orEmpty()
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
                val platform = platformLabel(stop?.binarioEffettivoPartenzaDescrizione ?: stop?.binarioProgrammatoPartenzaDescrizione)
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

    private fun minutesOfDay(hhmm: String): Int? =
        hhmm.split(":").takeIf { it.size == 2 }?.let { (h, m) -> h.toIntOrNull()?.let { hh -> m.toIntOrNull()?.let { hh * 60 + it } } }

    private fun nowCal() = java.util.Calendar.getInstance(ViaggiaTrenoApi.ROME)

    /** Riepilogo dei prossimi treni dalla stazione scelta, una volta al giorno all'orario impostato. */
    private suspend fun checkDigest() {
        val (sid, sname) = settings.digestStation.split("|", limit = 2).takeIf { it.size == 2 } ?: return
        val target = minutesOfDay(settings.digestTime) ?: return
        val cal = nowCal()
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        if (settings.digestWeekdays && (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY)) return
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        if (nowMin < target || nowMin > target + 40) return
        val day = "%04d%02d%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
        if (state.getBoolean("digest:$day", false)) return
        val dest = settings.digestDestination.split("|", limit = 2).takeIf { it.size == 2 }
        val lines = if (dest != null) {
            JourneySearch.direct(StationRef(sid, sname), StationRef(dest[0], dest[1]), Date()).sortedBy { it.departure }.take(5).map { j ->
                val l = j.legs.single()
                buildString {
                    append("${hm(l.departure)} ${l.title}")
                    j.platform?.takeIf { it.isNotBlank() }?.let { append(" · bin. $it") }
                    when {
                        j.cancelled -> append(" · SOPPRESSO")
                        (j.delay ?: 0) > 0 -> append(" · +${j.delay} min")
                    }
                }
            }
        } else {
            ViaggiaTrenoApi.departures(sid).take(6).map { e ->
                buildString {
                    append("${hm(e.orarioPartenza)} ${e.title} → ${e.destinazione.orEmpty()}")
                    platformLabel(e.binarioEffettivoPartenzaDescrizione ?: e.binarioProgrammatoPartenzaDescrizione)?.takeIf { it.isNotBlank() }?.let { append(" · bin. $it") }
                    when {
                        e.provvedimento == 1 -> append(" · SOPPRESSO")
                        (e.ritardo ?: 0) > 0 && e.nonPartito != true -> append(" · +${e.ritardo} min")
                    }
                }
            }
        }
        if (lines.isEmpty()) return
        state.edit().putBoolean("digest:$day", true).apply()
        Notifier.notify(
            applicationContext, Notifier.CH_DIGEST, "digest".hashCode(),
            "Prossimi treni da $sname" + (dest?.let { " per ${it[1]}" } ?: ""),
            lines.joinToString("\n"),
        )
    }

    /** Località dei viaggi dell'utente: prima parola del nome delle stazioni (es. "PADOVA", "CASTELFRANCO"). */
    private fun userPlaces(): Set<String> {
        val names = Graph.subscriptions.items.value.flatMap { listOf(it.fromName, it.toName) } +
            Graph.favorites.stations.value.map { it.name } +
            Graph.favorites.trains.value.flatMap { t ->
                // etichetta "FR 9600 MILANO CENTRALE → TORINO PORTA NUOVA": si toglie la sigla del treno
                t.label.split("→").mapIndexed { i, part -> if (i == 0) part.trim().split(" ").drop(2).joinToString(" ") else part.trim() }
            }
        return names.map { DetectionLocator.normalize(it).split(" ").firstOrNull().orEmpty() }
            .filter { it.length >= 4 }.toSet()
    }

    /** Avvisi di circolazione che citano le località dei tuoi viaggi. */
    private suspend fun checkDisruptions() {
        val places = userPlaces()
        if (places.isEmpty()) return
        ViaggiaTrenoApi.infomobility(works = false)
            .filter { categorize(it, false) == InfoCategory.CIRCULATION }
            .filter { n -> !n.title.lowercase().contains("regolare") }
            .filter { n ->
                val words = DetectionLocator.normalize(n.title + " " + android.text.Html.fromHtml(n.html, 0)).split(" ").toSet()
                places.any { it in words }
            }
            .take(3)
            .forEach { n ->
                if (once("disr:${n.title.hashCode()}")) {
                    Notifier.notify(applicationContext, Notifier.CH_DISRUPTIONS, n.title.hashCode(), "Disagi sulla tua linea", n.title, Notifier.EXTRA_INFO to "1")
                }
            }
    }

    /** Treni compatibili con gli abbonamenti attivi intorno agli orari abituali: ritardi e soppressioni. */
    private suspend fun checkSubscriptionTrains() {
        val cal = nowCal()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val midnight = System.currentTimeMillis() - nowMin * 60_000L - cal.get(java.util.Calendar.SECOND) * 1000L
        for (s in Graph.subscriptions.items.value.filter { it.isActive() }) {
            for ((time, reverse) in listOf(s.outboundTime to false, s.returnTime to true)) {
                val t = minutesOfDay(time) ?: continue
                if (nowMin < t - 60 || nowMin > t + 40) continue
                val center = midnight + t * 60_000L
                val trains = compatibleTrains(s, reverse, Date(maxOf(System.currentTimeMillis(), center - 40 * 60_000L))).compatible
                    .filter { it.departure in (center - 40 * 60_000L)..(center + 40 * 60_000L) }
                for (j in trains) {
                    val l = j.legs.single()
                    val key = "subtr:${l.number}:${l.dateMs}"
                    val extra = Notifier.EXTRA_TRAIN to "${l.number}|${l.originId}|${l.dateMs}"
                    val route = "${l.from} → ${l.to}, partenza ${hm(l.departure)}"
                    if (j.cancelled) {
                        if (once("$key:cancel")) Notifier.notify(applicationContext, Notifier.CH_SUBS, key.hashCode(), "${l.title} soppresso", route, extra)
                        continue
                    }
                    val delay = j.delay ?: continue
                    val last = state.getInt("$key:delay", Int.MIN_VALUE)
                    if (delay >= settings.delayThreshold && (last == Int.MIN_VALUE || delay - last >= 5)) {
                        state.edit().putInt("$key:delay", delay).apply()
                        Notifier.notify(applicationContext, Notifier.CH_SUBS, key.hashCode(), "${l.title}: +$delay min",
                            route + (j.platform?.let { " · binario $it" } ?: ""), extra)
                    }
                }
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
