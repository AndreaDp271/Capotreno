package io.github.andreadp.trenolibero.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andreadp.trenolibero.R
import io.github.andreadp.trenolibero.data.Andamento
import io.github.andreadp.trenolibero.data.Fermata
import io.github.andreadp.trenolibero.data.Precision
import org.osmdroid.views.MapView

/** Colori di stato condivisi da schede e linea del percorso (come nella schermata del treno). */
object StateColors {
    val passed = Color(0xFF1565C0)
    val transit = Color(0xFFFF8F00)
    val todo = Color(0xFF78858F)
    val suppressed = Color(0xFFC62828)
}

// ---------------- Controlli laterali: posizione, zoom ----------------

/** Colonna laterale con il pulsante della posizione e lo zoom +/−. */
@Composable
fun MapControls(map: MapView, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MyLocationButton(map, Modifier)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 4.dp,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ZoomButton(plus = true) { map.controller.zoomIn() }
                HorizontalDivider(Modifier.width(24.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ZoomButton(plus = false) { map.controller.zoomOut() }
            }
        }
    }
}

@Composable
private fun ZoomButton(plus: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Canvas(Modifier.size(16.dp)) {
            val w = 2.4.dp.toPx()
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w, StrokeCap.Round)
            if (plus) drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w, StrokeCap.Round)
        }
    }
}

// ---------------- Scheda in basso sulla mappa ----------------

/**
 * Scheda moderna sulla mappa: intestazione sfumata nel colore dello stato, contenuto sotto.
 * Resta visibile (con l'ultimo contenuto) durante l'animazione di chiusura.
 */
@Composable
fun <T : Any> MapSheet(
    item: T?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    var last by remember { mutableStateOf(item) }
    if (item != null) last = item
    AnimatedVisibility(
        visible = item != null,
        modifier = modifier,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
        last?.let { content(it) }
    }
}

@Composable
fun SheetCard(
    color: Color,
    label: String,
    title: String,
    onClose: () -> Unit,
    icon: Int? = null,
    subtitle: String? = null,
    body: @Composable () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 10.dp,
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.78f).compositeOverDark())))
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (icon != null) {
                        Surface(color = Color.White, shape = CircleShape, modifier = Modifier.size(40.dp)) {
                            Icon(painterResource(icon), null, tint = color, modifier = Modifier.padding(9.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            label.uppercase(), color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            title, color = Color.White, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall) }
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Chiudi", tint = Color.White) }
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { body() }
        }
    }
}

/** Leggero scurimento per la seconda tinta della sfumatura. */
private fun Color.compositeOverDark(): Color = Color(red * 0.8f, green * 0.8f, blue * 0.8f, 1f)

/** Riquadro "Arrivo"/"Partenza": orario programmato, effettivo o previsto. */
@Composable
fun TimeTile(label: String, scheduled: Long, actual: Long?, delay: Int?, predicted: Int?, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val shownDelay = if (actual != null) delay else predicted
            val changed = shownDelay != null && shownDelay != 0
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    fmtTime(scheduled),
                    style = if (changed) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = if (changed) FontWeight.Normal else FontWeight.Bold,
                    textDecoration = if (changed) TextDecoration.LineThrough else null,
                    color = if (changed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (changed) {
                    Text(
                        fmtTime(actual ?: (scheduled + shownDelay!! * 60_000L)),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = delayColor(shownDelay),
                    )
                }
            }
            Text(
                when {
                    actual != null -> "effettivo"
                    predicted != null -> "previsto"
                    else -> "programmato"
                },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------- Schede della mappa del treno ----------------

sealed interface TrainMapSel {
    data class Stop(val index: Int) : TrainMapSel
    data class Detection(val index: Int) : TrainMapSel
}

private enum class StopKind(val label: String, val color: Color) {
    PASSED("Fermata passata", StateColors.passed),
    HERE("Il treno è qui", StateColors.passed),
    NEXT("Prossima fermata", StateColors.transit),
    TODO("Fermata da fare", StateColors.todo),
    SUPPRESSED("Fermata soppressa", StateColors.suppressed),
}

private fun stopKind(a: Andamento, i: Int): StopKind {
    val stops = a.fermate
    val f = stops[i]
    val lastReached = stops.indexOfLast { it.reached && !it.suppressed }
    val running = !a.cancelled && a.nonPartito != true && a.arrivato != true
    val next = if (a.cancelled || a.arrivato == true) -1
    else ((lastReached + 1)..stops.lastIndex).firstOrNull { !stops[it].suppressed } ?: -1
    return when {
        f.suppressed -> StopKind.SUPPRESSED
        i == lastReached && (a.arrivato == true && i == stops.lastIndex ||
            running && f.arrivoReale != null && f.partenzaReale == null) -> StopKind.HERE
        i <= lastReached -> StopKind.PASSED
        i == next -> StopKind.NEXT
        else -> StopKind.TODO
    }
}

@Composable
fun StopSheet(a: Andamento, index: Int, onStation: (Fermata) -> Unit, onClose: () -> Unit) {
    val f = a.fermate.getOrNull(index) ?: return
    val kind = stopKind(a, index)
    val started = a.nonPartito != true
    val delayNow = f.ritardo?.takeIf { it != 0 } ?: a.ritardo
    SheetCard(
        color = kind.color, label = kind.label, title = f.stazione, onClose = onClose,
        icon = if (kind == StopKind.HERE) R.drawable.ic_tab_train else R.drawable.ic_tab_station,
        subtitle = "Fermata ${index + 1} di ${a.fermate.size}",
    ) {
        if (f.suppressed) {
            Text("Il treno non ferma in questa stazione.", color = StateColors.suppressed, fontWeight = FontWeight.SemiBold)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (f.tipoFermata != "P" && f.arrivoTeorico != null) {
                    TimeTile("Arrivo", f.arrivoTeorico, f.arrivoReale, f.ritardoArrivo,
                        if (started && f.arrivoReale == null) delayNow else null, Modifier.weight(1f))
                }
                if (f.tipoFermata != "A" && f.partenzaTeorica != null) {
                    TimeTile("Partenza", f.partenzaTeorica, f.partenzaReale, f.ritardoPartenza,
                        if (started && f.partenzaReale == null) delayNow else null, Modifier.weight(1f))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (f.tipoFermata == "A") {
                PlatformBadge(f.binarioProgrammatoArrivoDescrizione, f.binarioEffettivoArrivoDescrizione)
            } else {
                PlatformBadge(
                    f.binarioProgrammatoPartenzaDescrizione ?: f.binarioProgrammatoArrivoDescrizione,
                    f.binarioEffettivoPartenzaDescrizione ?: f.binarioEffettivoArrivoDescrizione,
                )
            }
            Box(Modifier.weight(1f))
            FilledTonalButton(onClick = { onStation(f) }) { Text("Tabellone") }
        }
    }
}

@Composable
fun DetectionSheet(
    a: Andamento,
    name: String,
    time: Long?,
    precision: Precision,
    last: Boolean,
    onOpenTrain: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val ago = time?.let { ((System.currentTimeMillis() - it) / 60_000).coerceAtLeast(0) }
    val arrived = a.arrivato == true
    SheetCard(
        color = if (last && !arrived) StateColors.transit else StateColors.passed,
        label = when {
            last && arrived -> "Arrivato"
            last -> "Posizione attuale"
            else -> "Rilevamento precedente"
        },
        title = name,
        icon = R.drawable.ic_tab_train,
        subtitle = time?.let { "Rilevato alle ${fmtTime(it)}" + if (ago != null && ago <= 180) " · $ago min fa" else "" },
        onClose = onClose,
    ) {
        when (precision) {
            Precision.NEAR -> NoteChip("Posto tecnico: posizione indicativa")
            Precision.ESTIMATED -> NoteChip("Posizione stimata dagli orari")
            Precision.EXACT -> Unit
        }
        if (last) {
            val lastReached = a.fermate.indexOfLast { it.reached && !it.suppressed }
            val next = if (arrived || a.cancelled) null
            else a.fermate.drop(lastReached + 1).firstOrNull { !it.suppressed }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (next != null) {
                        Text("Prossima fermata", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(next.stazione, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sched = next.arrivoTeorico ?: next.programmata
                        val d = next.ritardo?.takeIf { it != 0 } ?: a.ritardo ?: 0
                        sched?.let { Text("arrivo previsto ${fmtTime(it + d * 60_000L)}", style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Text(a.destinazione.orEmpty(), fontWeight = FontWeight.Bold)
                    }
                }
                if (!a.cancelled && a.nonPartito != true) DelayBadge(a.ritardo)
            }
            if (onOpenTrain != null) {
                FilledTonalButton(onClick = onOpenTrain, modifier = Modifier.fillMaxWidth()) { Text("Apri il treno") }
            }
        }
    }
}

@Composable
private fun NoteChip(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
