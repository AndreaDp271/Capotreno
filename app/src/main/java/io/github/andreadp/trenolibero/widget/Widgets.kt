package io.github.andreadp.trenolibero.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as FixedColor
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.andreadp.trenolibero.MainActivity
import io.github.andreadp.trenolibero.R
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.data.InfoCategory
import io.github.andreadp.trenolibero.data.TicketCode
import io.github.andreadp.trenolibero.ui.categoryColor
import java.util.concurrent.TimeUnit

// ---------------- Stile comune ----------------

private object W {
    val bg = ColorProvider(day = Color(0xFFF7F8FA), night = Color(0xFF1C1F24))
    val fg = ColorProvider(day = Color(0xFF1B1D21), night = Color(0xFFECEFF3))
    val muted = ColorProvider(day = Color(0xFF5F6670), night = Color(0xFFA3ABB5))
    val line = ColorProvider(day = Color(0xFFE3E6EA), night = Color(0xFF2E333A))
    val onTime = Color(0xFF2E7D32)
    val late = Color(0xFFC62828)
    val early = Color(0xFF1565C0)
    val passed = Color(0xFF1565C0)
    val transit = Color(0xFFFF8F00)
    val sign = Color(0xFF263238)
    val signText = Color(0xFFFFD54F)
    val changed = Color(0xFFFFC400)
    val white = Color.White
}

private fun fixed(c: Color) = FixedColor(c)

fun delayColor(min: Int?): Color = when {
    min == null -> W.onTime
    min < 0 -> W.early
    min == 0 -> W.onTime
    else -> W.late
}

fun delayLabel(min: Int?): String = when {
    min == null -> ""
    min == 0 -> "in orario"
    min > 0 -> "+$min'"
    else -> "$min'"
}

/** Apre l'app su una schermata (stesse route della navigazione interna). */
private fun openRoute(ctx: Context, route: String) = actionStartActivity(
    Intent(ctx, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_ROUTE, route)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
)

@Composable
private fun Chip(text: String, bg: Color, fg: Color = W.white, size: Int = 11) {
    Box(GlanceModifier.background(bg).cornerRadius(6.dp).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(text, style = TextStyle(color = fixed(fg), fontSize = size.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

@Composable
private fun Platform(p: String?) {
    if (p.isNullOrBlank()) return
    Chip("Bin. $p", W.sign, W.signText)
}

@Composable
private fun DelayChip(min: Int?) {
    if (min == null) return
    Chip(delayLabel(min), delayColor(min))
}

/** Intestazione colorata: icona, titolo, sottotitolo, pulsante aggiorna. */
@Composable
private fun Header(color: Color, icon: Int, title: String, subtitle: String?, stale: Boolean) {
    Row(
        GlanceModifier.fillMaxWidth().background(color).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(icon), null, GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(fixed(W.white)))
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(title, style = TextStyle(color = fixed(W.white), fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            if (subtitle != null) {
                Text(
                    subtitle + if (stale) " · non aggiornato" else "",
                    style = TextStyle(color = fixed(Color(0xDDFFFFFF)), fontSize = 11.sp), maxLines = 1,
                )
            }
        }
        Box(
            GlanceModifier.size(36.dp).clickable(actionRunCallback<RefreshAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(R.drawable.ic_widget_refresh), "Aggiorna", GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(fixed(W.white)))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(GlanceModifier.fillMaxSize().background(W.bg).cornerRadius(20.dp)) { content() }
}

@Composable
private fun Empty(text: String) {
    Box(GlanceModifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = W.muted, fontSize = 13.sp))
    }
}

@Composable
private fun Divider() {
    Box(GlanceModifier.fillMaxWidth().height(1.dp).background(W.line)) {}
}

private fun updatedLabel(ms: Long) = "agg. ${hhmm(ms)}"

private suspend fun widgetId(ctx: Context, id: GlanceId) = GlanceAppWidgetManager(ctx).getAppWidgetId(id)

// ---------------- Treno ----------------

class TrainWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Graph.init(context)
        val wid = widgetId(context, id)
        val sel = WidgetPrefs.selection(context, wid)
        val state = sel?.let { loadCached(context, wid) { loadTrain(it) } }
        provideContent {
            Card {
                val t = state?.data
                if (t == null) {
                    Header(W.passed, R.drawable.ic_tab_train, "Treno", null, false)
                    Empty(if (sel == null) "Tieni premuto il widget per scegliere un treno" else state?.message ?: "Caricamento…")
                    return@Card
                }
                val color = when (t.phase) {
                    "cancelled" -> W.late
                    "arrived" -> W.onTime
                    "waiting" -> Color(0xFF455A64)
                    else -> W.passed
                }
                Header(color, R.drawable.ic_tab_train, t.title, t.route, state.stale)
                Column(
                    GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)
                        .clickable(openRoute(context, "train/${t.number}?origin=${t.originId}&date=${t.dateMs}")),
                ) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            t.status, GlanceModifier.defaultWeight(),
                            style = TextStyle(color = W.fg, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 2,
                        )
                        DelayChip(t.delay)
                    }
                    if (t.phase == "running" || t.phase == "standing") {
                        Spacer(GlanceModifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = t.progress,
                            modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                            color = fixed(W.transit),
                            backgroundColor = fixed(Color(0x33FF8F00)),
                        )
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    if (t.nextStop != null) {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(GlanceModifier.defaultWeight()) {
                                Text(
                                    if (t.phase == "waiting") "Partenza" else "Prossima fermata",
                                    style = TextStyle(color = W.muted, fontSize = 11.sp),
                                )
                                Text(
                                    "${t.nextStop} · ${t.nextTime ?: ""}",
                                    style = TextStyle(color = W.fg, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1,
                                )
                            }
                            Platform(t.platform)
                        }
                    }
                    if (t.detection != null && LocalSize.current.height > 150.dp) {
                        Spacer(GlanceModifier.height(4.dp))
                        Text(t.detection, style = TextStyle(color = W.muted, fontSize = 11.sp), maxLines = 1)
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Text(updatedLabel(t.updatedAt), style = TextStyle(color = W.muted, fontSize = 10.sp))
                }
            }
        }
    }
}

// ---------------- Partenze ----------------

class DeparturesWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Graph.init(context)
        val wid = widgetId(context, id)
        val sel = WidgetPrefs.selection(context, wid)
        val state = sel?.let { loadCached(context, wid) { loadDepartures(it) } }
        provideContent {
            Card {
                val d = state?.data
                if (d == null) {
                    Header(Color(0xFF0D2F8C), R.drawable.ic_tab_station, "Partenze", null, false)
                    Empty(if (sel == null) "Tieni premuto il widget per scegliere una stazione" else state?.message ?: "Caricamento…")
                    return@Card
                }
                Header(Color(0xFF0D2F8C), R.drawable.ic_tab_station, d.stationName, "Partenze · ${updatedLabel(d.updatedAt)}", state.stale)
                val rows = ((LocalSize.current.height.value - 52) / 38).toInt().coerceIn(1, d.rows.size.coerceAtLeast(1))
                Column(
                    GlanceModifier.fillMaxSize().padding(horizontal = 10.dp)
                        .clickable(openRoute(context, "station/${d.stationId}?name=${android.net.Uri.encode(d.stationName)}")),
                ) {
                    if (d.rows.isEmpty()) Empty("Nessuna partenza nelle prossime ore")
                    d.rows.take(rows).forEachIndexed { i, r ->
                        if (i > 0) Divider()
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.time, style = TextStyle(color = W.fg, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                            Spacer(GlanceModifier.width(8.dp))
                            Column(GlanceModifier.defaultWeight()) {
                                Text(
                                    r.destination, style = TextStyle(color = W.fg, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Chip(r.title, categoryColor(r.category), size = 10)
                                    Spacer(GlanceModifier.width(4.dp))
                                    if (r.cancelled) Chip("SOPPRESSO", W.late, size = 10) else DelayChip(r.delay)
                                }
                            }
                            Platform(r.platform)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Abbonamento ----------------

class SubscriptionWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Graph.init(context)
        val wid = widgetId(context, id)
        val sel = WidgetPrefs.selection(context, wid)
        val state = sel?.let { loadCached(context, wid) { loadSubscription(context, wid, it) } }
        provideContent {
            Card {
                val s = state?.data
                val color = Color(0xFF6A1B9A)
                if (s == null) {
                    Header(color, R.drawable.ic_tab_ticket, "Abbonamento", null, false)
                    Empty(if (sel == null) "Tieni premuto il widget per scegliere un abbonamento" else state?.message ?: "Caricamento…")
                    return@Card
                }
                Row(
                    GlanceModifier.fillMaxWidth().background(color).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(ImageProvider(R.drawable.ic_tab_ticket), null, GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(fixed(W.white)))
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text("${s.from} → ${s.to}", style = TextStyle(color = fixed(W.white), fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        Text(
                            s.name + " · " + updatedLabel(s.updatedAt) + if (state.stale) " · non aggiornato" else "",
                            style = TextStyle(color = fixed(Color(0xDDFFFFFF)), fontSize = 11.sp), maxLines = 1,
                        )
                    }
                    Box(
                        GlanceModifier.size(36.dp).clickable(
                            actionRunCallback<SwapAction>(actionParametersOf(SwapAction.WIDGET to wid, SwapAction.REVERSE to !s.reverse)),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(ImageProvider(R.drawable.ic_swap_horiz), "Inverti", GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(fixed(W.white)))
                    }
                    Box(GlanceModifier.size(36.dp).clickable(actionRunCallback<RefreshAction>()), contentAlignment = Alignment.Center) {
                        Image(ImageProvider(R.drawable.ic_widget_refresh), "Aggiorna", GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(fixed(W.white)))
                    }
                }
                val rows = ((LocalSize.current.height.value - 52) / 34).toInt().coerceAtLeast(1)
                Column(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp).clickable(openRoute(context, "subscription/${s.subId}"))) {
                    if (s.rows.isEmpty()) Empty("Nessun treno compatibile nelle prossime ore")
                    s.rows.take(rows).forEachIndexed { i, r ->
                        if (i > 0) Divider()
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.time} → ${r.arrival}", style = TextStyle(color = W.fg, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                            Spacer(GlanceModifier.width(8.dp))
                            Chip(r.title, categoryColor(r.category), size = 10)
                            Spacer(GlanceModifier.width(4.dp))
                            if (r.cancelled) Chip("SOPPRESSO", W.late, size = 10) else DelayChip(r.delay)
                            Spacer(GlanceModifier.defaultWeight())
                            Platform(r.platform)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Biglietto ----------------

class TicketWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Graph.init(context)
        val t = nextTicket()
        val code = t?.takeIf { it.codeText.isNotEmpty() }?.let { TicketCode.render(it.codeFormat, it.codeText) }
        provideContent {
            Card {
                val color = Color(0xFF00695C)
                if (t == null) {
                    Header(color, R.drawable.ic_tab_ticket, "Biglietto", null, false)
                    Empty("Nessun viaggio in programma: importa un biglietto nell'app")
                    return@Card
                }
                Header(color, R.drawable.ic_tab_ticket, t.title, listOf(t.date, t.departure).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null }, false)
                Row(
                    GlanceModifier.fillMaxSize().padding(10.dp).clickable(openRoute(context, "ticket/${t.id}")),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(GlanceModifier.defaultWeight()) {
                        if (t.trains.isNotEmpty()) {
                            Row {
                                t.trains.take(2).forEach { tr ->
                                    Chip("${tr.category} ${tr.number}".trim(), categoryColor(tr.category), size = 11)
                                    Spacer(GlanceModifier.width(4.dp))
                                }
                            }
                            Spacer(GlanceModifier.height(6.dp))
                        }
                        if (t.coach.isNotBlank() || t.seat.isNotBlank()) {
                            Text(
                                listOfNotNull(t.coach.takeIf { it.isNotBlank() }?.let { "Carrozza $it" }, t.seat.takeIf { it.isNotBlank() }?.let { "posto $it" }).joinToString(" · "),
                                style = TextStyle(color = W.fg, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            )
                        }
                        if (t.pnr.isNotBlank()) Text("PNR ${t.pnr}", style = TextStyle(color = W.muted, fontSize = 12.sp))
                        if (t.isSubscription && t.validTo.isNotBlank()) Text("Valido fino al ${t.validTo}", style = TextStyle(color = W.muted, fontSize = 12.sp))
                        Spacer(GlanceModifier.defaultWeight())
                        Text(if (code != null) "Tocca per il codice a schermo intero" else "Tocca per aprire il biglietto", style = TextStyle(color = W.muted, fontSize = 10.sp))
                    }
                    if (code != null) {
                        val side = (LocalSize.current.height.value - 64).coerceIn(56f, 160f).dp
                        Box(GlanceModifier.background(Color.White).cornerRadius(8.dp).padding(4.dp)) {
                            Image(ImageProvider(code), "Codice del biglietto", GlanceModifier.size(side))
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Infomobilità ----------------

class InfoWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Graph.init(context)
        val wid = widgetId(context, id)
        val state = loadCached(context, wid) { loadInfo() }
        provideContent {
            Card {
                val d = state.data
                val color = Color(0xFFE65100)
                Header(
                    color, R.drawable.ic_widget_info, "Infomobilità",
                    d?.let { (if (it.strikes > 0) "${it.strikes} scioperi · " else "") + updatedLabel(it.updatedAt) }, state.stale,
                )
                if (d == null) { Empty(state.message ?: "Caricamento…"); return@Card }
                val rows = ((LocalSize.current.height.value - 52) / 36).toInt().coerceAtLeast(1)
                Column(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp).clickable(openRoute(context, "info"))) {
                    if (d.rows.isEmpty()) Empty("Nessun avviso: circolazione regolare")
                    d.rows.take(rows).forEachIndexed { i, r ->
                        if (i > 0) Divider()
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            val (label, c) = when (r.category) {
                                InfoCategory.STRIKE.name -> "SCIOPERO" to W.late
                                InfoCategory.CIRCULATION.name -> "CIRCOLAZIONE" to W.transit
                                InfoCategory.WORKS.name -> "LAVORI" to Color(0xFF6D4C41)
                                else -> "INFO" to Color(0xFF546E7A)
                            }
                            Chip(label, c, size = 9)
                            Spacer(GlanceModifier.width(6.dp))
                            Text(r.title, GlanceModifier.defaultWeight(), style = TextStyle(color = W.fg, fontSize = 12.sp), maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Azioni, ricevitori, aggiornamento periodico ----------------

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = Widgets.updateAll(context)
}

class SwapAction : ActionCallback {
    companion object {
        val WIDGET = ActionParameters.Key<Int>("wid")
        val REVERSE = ActionParameters.Key<Boolean>("rev")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetPrefs.setReversed(context, parameters[WIDGET] ?: return, parameters[REVERSE] ?: return)
        SubscriptionWidget().update(context, glanceId)
    }
}

object Widgets {
    private val receivers = listOf(
        TrainWidgetReceiver::class.java, DeparturesWidgetReceiver::class.java, SubscriptionWidgetReceiver::class.java,
        TicketWidgetReceiver::class.java, InfoWidgetReceiver::class.java,
    )

    fun any(ctx: Context): Boolean {
        val m = AppWidgetManager.getInstance(ctx)
        return receivers.any { m.getAppWidgetIds(ComponentName(ctx, it)).isNotEmpty() }
    }

    suspend fun updateAll(ctx: Context) {
        Graph.init(ctx)
        TrainWidget().updateAll(ctx)
        DeparturesWidget().updateAll(ctx)
        SubscriptionWidget().updateAll(ctx)
        TicketWidget().updateAll(ctx)
        InfoWidget().updateAll(ctx)
    }

    /** Aggiornamento ogni 15 minuti (minimo consentito da Android) finché c'è almeno un widget. */
    fun schedule(ctx: Context) {
        if (!any(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "widgets", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES).build(),
        )
    }
}

class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Widgets.any(applicationContext)) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork("widgets")
            return Result.success()
        }
        Widgets.updateAll(applicationContext)
        return Result.success()
    }
}

abstract class BaseWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Widgets.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Widgets.schedule(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { WidgetPrefs.remove(context, it) }
    }
}

class TrainWidgetReceiver : BaseWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TrainWidget() }
class DeparturesWidgetReceiver : BaseWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = DeparturesWidget() }
class SubscriptionWidgetReceiver : BaseWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SubscriptionWidget() }
class TicketWidgetReceiver : BaseWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TicketWidget() }
class InfoWidgetReceiver : BaseWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = InfoWidget() }

