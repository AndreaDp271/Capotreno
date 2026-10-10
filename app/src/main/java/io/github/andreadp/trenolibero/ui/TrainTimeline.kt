package io.github.andreadp.trenolibero.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.andreadp.trenolibero.R
import io.github.andreadp.trenolibero.data.Andamento
import io.github.andreadp.trenolibero.data.DetectionLocator
import io.github.andreadp.trenolibero.data.Fermata
import kotlinx.coroutines.delay

/** Colori della timeline: coerenti con la mappa del treno (blu = percorso, grigio = da percorrere). */
private object Tl {
    val passed = Color(0xFF1565C0)
    val transit = Color(0xFFFF8F00)
    val todo = Color(0xFFB0B0B0)
    val heroMoving = listOf(Color(0xFF0D47A1), Color(0xFF1976D2))
    val heroArrived = listOf(Color(0xFF1B5E20), Color(0xFF43A047))
    val heroWaiting = listOf(Color(0xFF37474F), Color(0xFF607D8B))
    val heroCancelled = listOf(Color(0xFFB71C1C), Color(0xFFE53935))
}

/** Stato di un tratto della linea tra due righe. */
private enum class Seg { NONE, PASSED, TRANSIT, TODO }

/** Stato di una fermata rispetto alla posizione del treno. */
private enum class StopState { PASSED, HERE, NEXT, UPCOMING, SUPPRESSED }

private sealed interface TlRow {
    val key: String
    data class Stop(val index: Int, val f: Fermata, val state: StopState, val top: Seg, val bottom: Seg) : TlRow {
        override val key get() = "s$index"
    }
    data class Transit(val from: Fermata?, val to: Fermata?, val detection: String?, val detectionTime: Long?, val progress: Float?) : TlRow {
        override val key get() = "transit"
    }
}

/** Posizione del treno lungo il percorso, ricavata da fermate raggiunte e ultimo rilevamento. */
private class Position(a: Andamento, now: Long) {
    val stops = a.fermate
    val lastReached = stops.indexOfLast { it.reached && !it.suppressed }
    val running = !a.cancelled && a.nonPartito != true && a.arrivato != true && lastReached >= 0
    /** Fermo in stazione: arrivato all'ultima fermata raggiunta ma non ancora ripartito. */
    val standing = running && stops[lastReached].let { it.arrivoReale != null && it.partenzaReale == null } &&
        lastReached < stops.lastIndex
    val next = when {
        a.cancelled || a.arrivato == true -> -1
        lastReached < 0 -> stops.indexOfFirst { !it.suppressed }
        else -> (lastReached + 1..stops.lastIndex).firstOrNull { !stops[it].suppressed } ?: -1
    }
    val detection = a.stazioneUltimoRilevamento?.takeIf { it.isNotBlank() && it != "--" && a.arrivato != true }
    val detectionIsStop = detection != null && stops.any { DetectionLocator.normalize(it.stazione) == DetectionLocator.normalize(detection) }
    val inTransit = running && !standing && next >= 0

    /** Avanzamento stimato nel tratto corrente: partenza reale → arrivo previsto alla prossima fermata. */
    val legProgress: Float? = if (!inTransit) null else {
        val from = stops[lastReached]
        val to = stops[next]
        val start = from.partenzaReale ?: from.arrivoReale
        val sched = to.arrivoTeorico ?: to.programmata
        val delayMin = to.ritardo ?: a.ritardo ?: 0
        if (start == null || sched == null) null
        else {
            val end = sched + delayMin * 60_000L
            if (end <= start) null else ((now - start).toFloat() / (end - start)).coerceIn(0.03f, 0.97f)
        }
    }

    /** Avanzamento complessivo del viaggio (per la barra in alto). */
    val tripProgress: Float = when {
        a.arrivato == true -> 1f
        a.cancelled || a.nonPartito == true || lastReached < 0 || stops.size < 2 -> 0f
        else -> {
            val t0 = stops.first().partenzaTeorica ?: stops.first().programmata
            val t1 = stops.last().arrivoTeorico ?: stops.last().programmata
            val delay = (a.ritardo ?: 0) * 60_000L
            val byTime = if (t0 != null && t1 != null && t1 > t0) ((now - delay - t0).toFloat() / (t1 - t0)) else null
            val byStops = lastReached.toFloat() / stops.lastIndex
            (byTime ?: byStops).coerceIn(byStops, if (next >= 0) next.toFloat() / stops.lastIndex else 1f)
        }
    }

    fun seg(i: Int): Seg = when {           // tratto da fermata i a i+1
        i < 0 || i >= stops.lastIndex -> Seg.NONE
        i + 1 <= lastReached -> Seg.PASSED
        inTransit && i >= lastReached && i < next -> Seg.TRANSIT
        else -> Seg.TODO
    }

    fun rows(a: Andamento): List<TlRow> = buildList {
        stops.forEachIndexed { i, f ->
            val state = when {
                f.suppressed -> StopState.SUPPRESSED
                i == lastReached && (standing || a.arrivato == true && i == stops.lastIndex) -> StopState.HERE
                i <= lastReached -> StopState.PASSED
                i == next -> StopState.NEXT
                else -> StopState.UPCOMING
            }
            val transitHere = inTransit && i == lastReached
            add(TlRow.Stop(i, f, state, seg(i - 1), seg(i)))
            if (transitHere) {
                add(TlRow.Transit(f, stops[next], detection?.takeIf { !detectionIsStop }, a.oraUltimoRilevamento, legProgress))
            }
        }
    }
}

@Composable
internal fun TrainDetail(nav: Nav, a: Andamento) {
    // orologio locale: l'avanzamento nel tratto cresce anche tra un aggiornamento e l'altro
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val pos = remember(a, now) { Position(a, now) }
    val rows = remember(pos) { pos.rows(a) }

    val transition = rememberInfiniteTransition(label = "tl")
    val dashPhase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "dash")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing)), label = "pulse")
    val dash = { dashPhase }
    val pulseValue = { pulse }

    val list = rememberLazyListState()
    var scrolled by remember { mutableStateOf(false) }
    LaunchedEffect(rows) {
        if (!scrolled) {
            scrolled = true
            val focus = rows.indexOfFirst { it is TlRow.Transit || (it as? TlRow.Stop)?.state == StopState.HERE }
                .takeIf { it >= 0 } ?: rows.indexOfFirst { (it as? TlRow.Stop)?.state == StopState.NEXT }
            // +2: intestazione e legenda prima delle fermate; si lascia visibile una fermata sopra
            if (focus > 1) list.animateScrollToItem(focus + 1)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = list) {
        item(key = "hero") { TrainHero(a, pos) }
        item(key = "legend") { TimelineLegend(pos) }
        items(rows, key = { it.key }) { r ->
            when (r) {
                is TlRow.Stop -> StopRow(
                    r, a, dash, pulseValue,
                    trainStarted = a.nonPartito != true,
                    onClick = { nav.station(r.f.id, r.f.stazione) },
                )
                is TlRow.Transit -> TransitRow(r, a, dash, pulseValue)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------- Intestazione ----------------

@Composable
private fun TrainHero(a: Andamento, pos: Position) {
    val colors = when {
        a.cancelled -> Tl.heroCancelled
        a.arrivato == true -> Tl.heroArrived
        a.nonPartito == true -> Tl.heroWaiting
        else -> Tl.heroMoving
    }
    val status = when {
        a.cancelled -> "Treno soppresso"
        a.nonPartito == true -> "Non ancora partito"
        a.arrivato == true -> "Arrivato a destinazione"
        pos.standing -> "Fermo a ${pos.stops[pos.lastReached].stazione}"
        else -> "In viaggio"
    }
    val onHero = Color.White
    Box(
        Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(colors)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pos.running) LiveDot(onHero)
                Text(status, color = onHero, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                if (!a.cancelled && a.nonPartito != true) DelayBadge(a.ritardo)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(fmtTime(a.orarioPartenza), color = onHero, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(a.origine.orEmpty(), color = onHero.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    a.compDurata?.takeIf { it.isNotBlank() }.orEmpty(),
                    color = onHero.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(fmtTime(a.orarioArrivo), color = onHero, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(a.destinazione.orEmpty(), color = onHero.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            TripProgress(pos.tripProgress, pos.running, onHero)
            val passed = pos.stops.count { it.reached && !it.suppressed }
            val total = pos.stops.count { !it.suppressed }
            Text(
                "$passed di $total fermate" + if (pos.next >= 0 && !a.cancelled) " · prossima: ${pos.stops[pos.next].stazione}" else "",
                color = onHero.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall,
            )
            if (pos.detection != null && a.oraUltimoRilevamento != null) {
                val ago = ((System.currentTimeMillis() - a.oraUltimoRilevamento) / 60_000).coerceAtLeast(0)
                Text(
                    "📍 Rilevato a ${pos.detection} alle ${fmtTime(a.oraUltimoRilevamento)}" + if (ago in 0..180) " ($ago min fa)" else "",
                    color = onHero, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (a.partiallyCancelled) HeroNote("Soppresso parzialmente")
            if (a.deviated) HeroNote("Treno deviato")
            a.motivoRitardoPrevalente?.takeIf { it.isNotBlank() }?.let { HeroNote("Causa: $it") }
            a.subTitle?.takeIf { it.isNotBlank() }?.let { Text(it, color = onHero.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall) }
            a.descOrientamento?.firstOrNull()?.takeIf { it.isNotBlank() && it != "--" }?.let {
                Text(android.text.Html.fromHtml(it, 0).toString(), color = onHero.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HeroNote(text: String) {
    Surface(color = Color.White.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Pallino "live" pulsante. */
@Composable
private fun LiveDot(color: Color) {
    val t = rememberInfiniteTransition(label = "live")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "p")
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).scale(0.5f + p).alpha(1f - p).background(color.copy(alpha = 0.6f), CircleShape))
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

/** Barra di avanzamento del viaggio con il trenino che scorre. */
@Composable
private fun TripProgress(target: Float, running: Boolean, color: Color) {
    val p by animateFloatAsState(target, tween(1200, easing = FastOutSlowInEasing), label = "trip")
    BoxWithConstraints(Modifier.fillMaxWidth().height(26.dp)) {
        val w = maxWidth
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(6.dp).clip(CircleShape).background(color.copy(alpha = 0.25f)))
        Box(Modifier.align(Alignment.CenterStart).width(w * p).height(6.dp).clip(CircleShape).background(color))
        if (running || p > 0f) {
            val icon = 24.dp
            Surface(
                color = color, shape = CircleShape, shadowElevation = 3.dp,
                modifier = Modifier.size(icon).offset(x = ((w - icon) * p)),
            ) {
                Icon(painterResource(R.drawable.ic_tab_train), null, tint = Tl.heroMoving[0], modifier = Modifier.padding(4.dp))
            }
        }
    }
}

@Composable
private fun TimelineLegend(pos: Position) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Fermate", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        LegendItem(Tl.passed, "passate")
        if (pos.inTransit) LegendItem(Tl.transit, "in corso")
        LegendItem(Tl.todo, "da fare")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.width(14.dp).height(4.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------- Linea del percorso ----------------

private val RailWidth = 56.dp

/** Disegna metà superiore e inferiore della linea; il tratto in corso è tratteggiato e scorre verso il basso. */
@Composable
private fun RailCanvas(top: Seg, bottom: Seg, split: Float, dash: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier.width(RailWidth).fillMaxHeight()) {
        val x = size.width / 2
        val mid = size.height * split
        fun draw(seg: Seg, y0: Float, y1: Float) {
            if (seg == Seg.NONE || y1 <= y0) return
            val strokeW = when (seg) { Seg.TODO -> 3.dp.toPx(); else -> 5.dp.toPx() }
            when (seg) {
                Seg.TRANSIT -> {
                    val on = 9.dp.toPx(); val off = 7.dp.toPx()
                    drawLine(Tl.transit.copy(alpha = 0.22f), Offset(x, y0), Offset(x, y1), strokeW)
                    drawLine(
                        Tl.transit, Offset(x, y0), Offset(x, y1), strokeW, StrokeCap.Round,
                        PathEffect.dashPathEffect(floatArrayOf(on, off), -(on + off) * dash()),
                    )
                }
                Seg.PASSED -> drawLine(Tl.passed, Offset(x, y0), Offset(x, y1), strokeW)
                else -> drawLine(Tl.todo, Offset(x, y0), Offset(x, y1), strokeW)
            }
        }
        draw(top, 0f, mid)
        draw(bottom, mid, size.height)
    }
}

@Composable
private fun StopDot(state: StopState, pulse: () -> Float) {
    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        when (state) {
            StopState.PASSED -> Box(Modifier.size(20.dp).background(Tl.passed, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
            StopState.HERE -> {
                Canvas(Modifier.size(34.dp)) {
                    val p = pulse()
                    drawCircle(Tl.passed.copy(alpha = 0.35f * (1 - p)), radius = size.minDimension / 2 * (0.6f + 0.4f * p))
                }
                Surface(color = Tl.passed, shape = CircleShape, shadowElevation = 3.dp, modifier = Modifier.size(26.dp)) {
                    Icon(painterResource(R.drawable.ic_tab_train), null, tint = Color.White, modifier = Modifier.padding(5.dp))
                }
            }
            StopState.NEXT -> {
                Canvas(Modifier.size(34.dp)) {
                    val p = pulse()
                    drawCircle(Tl.transit.copy(alpha = 0.45f * (1 - p)), radius = size.minDimension / 2 * (0.55f + 0.45f * p))
                }
                Box(
                    Modifier.size(20.dp).background(MaterialTheme.colorScheme.surface, CircleShape).border(4.dp, Tl.transit, CircleShape),
                )
            }
            StopState.UPCOMING -> Box(
                Modifier.size(16.dp).background(MaterialTheme.colorScheme.surface, CircleShape).border(3.dp, Tl.todo, CircleShape),
            )
            StopState.SUPPRESSED -> Box(Modifier.size(16.dp).background(Palette.veryLate, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.width(8.dp).height(2.dp).background(Color.White))
            }
        }
    }
}

@Composable
private fun StopRow(
    r: TlRow.Stop,
    a: Andamento,
    dash: () -> Float,
    pulse: () -> Float,
    trainStarted: Boolean,
    onClick: () -> Unit,
) {
    val f = r.f
    val highlight = r.state == StopState.NEXT || r.state == StopState.HERE
    val bg = when (r.state) {
        StopState.NEXT -> Tl.transit.copy(alpha = 0.12f)
        StopState.HERE -> Tl.passed.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val dim = r.state == StopState.PASSED
    val textColor = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (highlight) 84.dp else 68.dp)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .background(bg)
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(RailWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
            RailCanvas(r.top, r.bottom, 0.5f, dash)
            StopDot(r.state, pulse)
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (r.state) {
                StopState.NEXT -> Tag(if (a.nonPartito == true) "PARTENZA" else "PROSSIMA FERMATA", Tl.transit)
                StopState.HERE -> Tag(if (a.arrivato == true) "ARRIVATO" else "IL TRENO È QUI", Tl.passed)
                else -> {}
            }
            Text(
                f.stazione,
                fontWeight = if (highlight) FontWeight.ExtraBold else FontWeight.SemiBold,
                style = if (highlight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (f.suppressed) TextDecoration.LineThrough else null,
            )
            if (f.suppressed) {
                Text("Fermata soppressa", color = Palette.veryLate, style = MaterialTheme.typography.bodySmall)
            } else {
                // Per le fermate non ancora raggiunte si mostra l'orario previsto (programmato + ritardo attuale)
                val predictDelay = if (trainStarted && !f.reached) (f.ritardo ?: a.ritardo) else null
                if (f.tipoFermata != "P" && f.arrivoTeorico != null) {
                    TimeLine("Arr.", f.arrivoTeorico, f.arrivoReale, f.ritardoArrivo, predictDelay, dim)
                }
                if (f.tipoFermata != "A" && f.partenzaTeorica != null) {
                    TimeLine("Part.", f.partenzaTeorica, f.partenzaReale, f.ritardoPartenza, predictDelay, dim)
                }
            }
        }
        if (f.tipoFermata == "A") {
            PlatformBadge(f.binarioProgrammatoArrivoDescrizione, f.binarioEffettivoArrivoDescrizione)
        } else {
            PlatformBadge(
                f.binarioProgrammatoPartenzaDescrizione ?: f.binarioProgrammatoArrivoDescrizione,
                f.binarioEffettivoPartenzaDescrizione ?: f.binarioEffettivoArrivoDescrizione,
            )
        }
    }
}

/** Riga tra l'ultima fermata raggiunta e la prossima: il treno in movimento. */
@Composable
private fun TransitRow(r: TlRow.Transit, a: Andamento, dash: () -> Float, pulse: () -> Float) {
    Row(
        Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(RailWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
            // sopra il rilevamento il tratto è già percorso
            RailCanvas(if (r.detection != null) Seg.PASSED else Seg.TRANSIT, Seg.TRANSIT, 0.5f, dash)
            Canvas(Modifier.size(44.dp)) {
                val p = pulse()
                drawCircle(Tl.transit.copy(alpha = 0.4f * (1 - p)), radius = size.minDimension / 2 * (0.5f + 0.5f * p))
            }
            Surface(color = Tl.transit, shape = CircleShape, shadowElevation = 4.dp, modifier = Modifier.size(30.dp)) {
                Icon(painterResource(R.drawable.ic_tab_train), "Treno", tint = Color.White, modifier = Modifier.padding(6.dp))
            }
        }
        Surface(
            color = Tl.transit.copy(alpha = 0.14f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "In viaggio verso ${r.to?.stazione.orEmpty()}",
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val sub = buildList {
                    if (r.detection != null) add("rilevato a ${r.detection}" + (r.detectionTime?.let { " alle ${fmtTime(it)}" } ?: ""))
                    else r.from?.let { f -> (f.partenzaReale ?: f.arrivoReale)?.let { add("partito da ${f.stazione} alle ${fmtTime(it)}") } }
                    r.to?.let { t ->
                        val sched = t.arrivoTeorico ?: t.programmata
                        val d = t.ritardo ?: a.ritardo ?: 0
                        if (sched != null) add("arrivo previsto ${fmtTime(sched + d * 60_000L)}")
                    }
                }
                if (sub.isNotEmpty()) {
                    Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                r.progress?.let { target ->
                    val p by animateFloatAsState(target, tween(1000), label = "leg")
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Tl.transit.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(CircleShape).background(Tl.transit))
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(6.dp)) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

@Composable
private fun TimeLine(label: String, programmed: Long, actual: Long?, delay: Int?, predictDelay: Int?, dim: Boolean) {
    val base = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "$label ${fmtTime(programmed)}", style = MaterialTheme.typography.bodyMedium, color = base,
            textDecoration = if (actual != null && delay != null && delay != 0) TextDecoration.LineThrough else null,
        )
        when {
            actual != null -> Text("${fmtTime(actual)}", style = MaterialTheme.typography.bodyMedium, color = delayColor(delay), fontWeight = FontWeight.Bold)
            predictDelay != null && predictDelay != 0 -> Text(
                "prev. ${fmtTime(programmed + predictDelay * 60_000L)}",
                style = MaterialTheme.typography.bodySmall, color = delayColor(predictDelay), fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
