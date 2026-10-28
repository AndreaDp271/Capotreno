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

    /** Avvisi dei monitor RFI delle stazioni preferite: attivi di default, serve solo salvare una stazione. */
    var stationNotices: Boolean
        get() = p.getBoolean("stationNotices", true)
        set(v) = p.edit().putBoolean("stationNotices", v).apply()

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
    const val CH_LIVE = "seguito"
    const val CH_FREQUENT = "frequenti"
    const val CH_STATIONS = "stazioni"
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
                .apply { description = "Guasti e rallentamenti nelle località dei tuoi viaggi e delle linee preferite" },
            NotificationChannel(CH_FREQUENT, "Viaggi frequenti", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Avviso prima della partenza dei treni dei tuoi viaggi frequenti" },
            NotificationChannel(CH_STATIONS, "Avvisi di stazione", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Avvisi dei monitor delle stazioni preferite" },
            NotificationChannel(CH_LIVE, "Treno seguito", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Notifica fissa con la linea delle fermate del treno che stai seguendo"; setShowBadge(false) },
        ).forEach { nm.createNotificationChannel(it) }
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Avvia o ferma il controllo periodico in base alle impostazioni. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val s = NotifySettings(ctx)
        val lines = !ctx.getSharedPreferences("favorites", Context.MODE_PRIVATE).getStringSet("lines", emptySet()).isNullOrEmpty()
        val stations = lines || s.stationNotices && !ctx.getSharedPreferences("favorites", Context.MODE_PRIVATE).getStringSet("stations", emptySet()).isNullOrEmpty()
        if (!s.anyEnabled && !stations && !FrequentTrips.anyEnabled(ctx)) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private val subTexts = mapOf(
        CH_TRAINS to "Treno preferito", CH_TICKETS to "Biglietto", CH_STRIKES to "Scioperi", CH_SUBS to "Abbonamento",
        CH_ALARMS to "Sveglia fermata", CH_DIGEST to "Riepilogo partenze", CH_DISRUPTIONS to "Disagi", CH_LIVE to "Treno seguito",
        CH_FREQUENT to "Viaggio frequente", CH_STATIONS to "Stazione preferita",
    )

    /** Base comune: icona, colore, categoria e tocco che apre l'app sulla schermata giusta. */
    fun builder(ctx: Context, channel: String, id: Int, extra: Pair<String, String>?): NotificationCompat.Builder {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            extra?.let { putExtra(it.first, it.second) }
        }
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_tab_train)
            .setColor(TrainBanner.ACCENT)
            .setSubText(subTexts[channel])
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(if (channel == CH_ALARMS) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (channel == CH_TRAINS || channel == CH_TICKETS || channel == CH_ALARMS || channel == CH_FREQUENT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
    }

    /**
     * Con [hero] la notifica è una card a gradiente (un dato grande e una tessera); [follow] aggiunge il pulsante "Segui"
     * per il treno in [extra]. Senza [hero], o se la card non viene accettata, è testo lungo.
     */
    fun notify(
        ctx: Context, channel: String, id: Int, title: String, text: String, extra: Pair<String, String>? = null,
        hero: NotificationCard.Hero? = null, follow: Boolean = false,
    ) {
        if (!canNotify(ctx)) return
        fun plain() = builder(ctx, channel, id, extra).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        val nm = NotificationManagerCompat.from(ctx)
        if (hero != null) {
            val card = runCatching {
                val b = plain()
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setCustomContentView(NotificationCard.small(ctx, hero))
                    .setCustomBigContentView(NotificationCard.big(ctx, hero))
                val value = extra?.takeIf { it.first == EXTRA_TRAIN }?.second
                if (follow && value != null && value !in LiveTrain.all(ctx)) {
                    b.addAction(R.drawable.ic_tab_train, "Segui", LiveTrain.followIntent(ctx, value))
                }
                @Suppress("MissingPermission")
                nm.notify(id, b.build())
            }
            if (card.isSuccess) return
        }
        @Suppress("MissingPermission")
        nm.notify(id, plain().build())
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
        if (settings.disruptions || Graph.favorites.lines.value.isNotEmpty()) runCatching { checkDisruptions() }
        if (settings.subscriptionTrains) runCatching { checkSubscriptionTrains() }
        if (settings.stationNotices) runCatching { checkStationNotices() }
        runCatching { FrequentTrips.plan(applicationContext, state) }
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
        val route = "${TrainBanner.pretty(a.origine.orEmpty())} → ${TrainBanner.pretty(a.destinazione.orEmpty())}"
        if (a.cancelled && once("$key:cancel")) {
            Notifier.notify(applicationContext, channel, id, "$prefix${a.title} soppresso", route, extra, TrainAlerts.cancelled(a), follow = false); return
        }
        val delay = a.ritardo ?: 0
        val last = state.getInt("$key:delay", Int.MIN_VALUE)
        if (a.nonPartito != true && delay >= settings.delayThreshold && (last == Int.MIN_VALUE || delay - last >= 5)) {
            state.edit().putInt("$key:delay", delay).apply()
            val where = a.stazioneUltimoRilevamento?.takeIf { it.isNotBlank() && it != "--" }
                ?.let { "\nUltimo rilevamento: $it alle ${hm(a.oraUltimoRilevamento)}" }.orEmpty()
            Notifier.notify(applicationContext, channel, id, "$prefix${a.title}: +$delay min", route + where, extra, TrainAlerts.delay(a), follow = true)
        }
        val prog = platformLabel(platformStop?.binarioProgrammatoPartenzaDescrizione).orEmpty()
        val eff = platformLabel(platformStop?.binarioEffettivoPartenzaDescrizione).orEmpty()
        if (eff.isNotEmpty() && prog.isNotEmpty() && eff != prog && once("$key:platform:$eff")) {
            Notifier.notify(
                applicationContext, channel, id + 1, "$prefix${a.title}: cambio binario",
                "A ${TrainBanner.pretty(platformStop?.stazione.orEmpty())} parte dal binario $eff (era $prog)", extra,
                TrainAlerts.platformChange(a, platformStop, prog, eff), follow = true,
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
                ).joinToString(", ").replaceFirstChar { c -> c.uppercase() }
                Notifier.notify(
                    applicationContext, Notifier.CH_TICKETS, "ticket:${t.id}".hashCode(),
                    "Tra $minutes min parte il tuo ${a.title}",
                    "Alle ${hm(dep)} da ${TrainBanner.pretty(stop?.stazione ?: t.from)}$platform$delay" + if (seat.isEmpty()) "" else "\n$seat", extra,
                    TrainAlerts.reminder(a, stop, minutes, t.to, seat.ifEmpty { null }), follow = true,
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
                Notifier.notify(applicationContext, Notifier.CH_STRIKES, n.title.hashCode(), "Sciopero", fullText(n), Notifier.EXTRA_INFO to "1", TrainAlerts.strike(n))
            }
        }
    }

    /** Titolo dell'avviso seguito dal testo, per la notifica espansa. */
    private fun fullText(n: InfoNews): String {
        val body = n.plainText()
        return if (body.isEmpty() || body.startsWith(n.title)) n.title else n.title + "\n" + body.take(700)
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
        val rows = if (dest != null) {
            JourneySearch.direct(StationRef(sid, sname), StationRef(dest[0], dest[1]), Date()).sortedBy { it.departure }.take(5).map { j ->
                val l = j.legs.single()
                val bin = j.platform?.takeIf { it.isNotBlank() }?.let { "bin. $it" }
                when {
                    j.cancelled -> NotificationCard.Row(hm(l.departure), l.title, "soppresso", bad = true, gone = true)
                    (j.delay ?: 0) > 0 -> NotificationCard.Row(hm(l.departure), l.title, "+${j.delay}'", bad = true)
                    else -> NotificationCard.Row(hm(l.departure), l.title, bin)
                }
            }
        } else {
            ViaggiaTrenoApi.departures(sid).take(6).map { e ->
                val bin = platformLabel(e.binarioEffettivoPartenzaDescrizione ?: e.binarioProgrammatoPartenzaDescrizione)?.takeIf { it.isNotBlank() }?.let { "bin. $it" }
                when {
                    e.provvedimento == 1 -> NotificationCard.Row(hm(e.orarioPartenza), e.title, "soppresso", bad = true, gone = true)
                    (e.ritardo ?: 0) > 0 && e.nonPartito != true -> NotificationCard.Row(hm(e.orarioPartenza), e.title, "+${e.ritardo}'", bad = true)
                    else -> NotificationCard.Row(hm(e.orarioPartenza), e.title, bin)
                }
            }
        }
        if (rows.isEmpty()) return
        state.edit().putBoolean("digest:$day", true).apply()
        val title = "Da ${TrainBanner.pretty(sname)}" + (dest?.let { " per ${TrainBanner.pretty(it[1])}" } ?: "")
        Notifier.notify(
            applicationContext, Notifier.CH_DIGEST, "digest".hashCode(), title,
            rows.joinToString("\n") { listOfNotNull(it.time, it.name, it.tag).joinToString(" ") },
            hero = TrainAlerts.digest(title, rows),
        )
    }

    /** Località dei viaggi dell'utente: prima parola del nome delle stazioni (es. "ROMA", "MILANO"). */
    private fun userPlaces(): Set<String> {
        val fromLines = Graph.favorites.lines.value.flatMap { it.places }.toSet()
        if (!settings.disruptions) return fromLines     // solo linee preferite
        val names = Graph.subscriptions.items.value.flatMap { listOf(it.fromName, it.toName) } +
            Graph.favorites.stations.value.map { it.name } +
            Graph.favorites.trains.value.flatMap { t ->
                // etichetta "FR 9600 MILANO CENTRALE → TORINO PORTA NUOVA": si toglie la sigla del treno
                t.label.split("→").mapIndexed { i, part -> if (i == 0) part.trim().split(" ").drop(2).joinToString(" ") else part.trim() }
            }
        return names.mapNotNull { placeKey(it) }.toSet() + fromLines
    }

    /** Avvisi di circolazione che citano le località dei tuoi viaggi. */
    private suspend fun checkDisruptions() {
        val places = userPlaces()
        if (places.isEmpty()) return
        alertsFor(ViaggiaTrenoApi.infomobility(works = false), places)
            .filter { categorize(it, false) == InfoCategory.CIRCULATION }
            .filter { n -> !n.title.lowercase().contains("regolare") }
            .take(3)
            .forEach { n ->
                if (once("disr:${n.title.hashCode()}")) {
                    Notifier.notify(applicationContext, Notifier.CH_DISRUPTIONS, n.title.hashCode(), "Disagi sulla tua linea", fullText(n), Notifier.EXTRA_INFO to "1", TrainAlerts.disruption(n))
                }
            }
    }

    /** Avvisi dei monitor RFI delle stazioni preferite (esclusi quelli fissi di sicurezza), una volta sola ciascuno. */
    private suspend fun checkStationNotices() {
        for (st in Graph.favorites.stations.value.take(8)) {
            val notices = RfiMonitor.notices(applicationContext, st.name).orEmpty().filterNot { RfiMonitor.isStanding(it) }
            for (text in notices.take(2)) {
                val key = "stn:${st.id}:${text.hashCode()}"
                if (!once(key)) continue
                Notifier.notify(
                    applicationContext, Notifier.CH_STATIONS, key.hashCode(), TrainBanner.pretty(st.name), text,
                    hero = TrainAlerts.stationNotice(st.name, text),
                )
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
                    val route = "${TrainBanner.pretty(l.from)} → ${TrainBanner.pretty(l.to)}, partenza ${hm(l.departure)}"
                    // l'andamento completo serve solo per il banner, quando c'è davvero da avvisare
                    suspend fun status() = if (l.originId == null || l.dateMs == null) null
                        else runCatching { ViaggiaTrenoApi.trainStatus(l.originId, l.number, l.dateMs) }.getOrNull()
                    if (j.cancelled) {
                        if (once("$key:cancel")) {
                            val a = status()
                            Notifier.notify(applicationContext, Notifier.CH_SUBS, key.hashCode(), "${l.title} soppresso", route, extra,
                                a?.let { TrainAlerts.cancelled(it, l.departure) })
                        }
                        continue
                    }
                    val delay = j.delay ?: continue
                    val last = state.getInt("$key:delay", Int.MIN_VALUE)
                    if (delay >= settings.delayThreshold && (last == Int.MIN_VALUE || delay - last >= 5)) {
                        state.edit().putInt("$key:delay", delay).apply()
                        val a = status()
                        Notifier.notify(applicationContext, Notifier.CH_SUBS, key.hashCode(), "${l.title}: +$delay min",
                            route + (j.platform?.let { " · binario $it" } ?: ""), extra,
                            a?.let { TrainAlerts.delay(it, l.to) }, follow = true)
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
                    TrainAlerts.expiry(s.name.ifEmpty { "${TrainBanner.pretty(s.fromName)} – ${TrainBanner.pretty(s.toName)}" }, days, s.validTo),
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
