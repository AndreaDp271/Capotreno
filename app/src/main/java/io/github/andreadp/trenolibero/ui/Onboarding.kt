package io.github.andreadp.trenolibero.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.andreadp.trenolibero.R
import kotlinx.coroutines.launch

private enum class Perm { NONE, NOTIFICATIONS, LOCATION, CAMERA }

private data class GuidePage(val art: @Composable () -> Unit, val title: String, val text: String, val perm: Perm = Perm.NONE)

private val guidePages = listOf(
    GuidePage(
        { ArtLogo() }, "Benvenuto",
        "Segui i treni italiani in tempo reale, senza account e senza pubblicità. Scorri per scoprire cosa puoi fare: ci vuole un minuto.",
    ),
    GuidePage(
        { Shot(R.drawable.guide_board) }, "Stazioni e tabelloni",
        "Cerca una stazione e vedi partenze e arrivi con ritardo, binario e avvisi. Con il cuore salvi stazioni, treni e linee tra i preferiti.",
    ),
    GuidePage(
        { Shot(R.drawable.guide_train) }, "Il treno in diretta",
        "Cerca un treno per numero: vedi dov'è, il ritardo, le fermate e i binari, aggiornati in automatico.",
    ),
    GuidePage(
        { Shot(R.drawable.guide_follow) }, "Banner e avvisi",
        "Tocca il cuore su un treno per seguirlo con un banner in notifica, oppure per essere avvisato prima di una fermata. " +
            "Ricevi anche gli avvisi su scioperi, lavori e disagi sulle tue stazioni e linee.",
        Perm.NOTIFICATIONS,
    ),
    GuidePage(
        { Shot(R.drawable.guide_map) }, "Mappa e vicino a te",
        "Segui il percorso del treno sulla mappa e scopri le stazioni vicine a te. La posizione resta sul telefono, non viene inviata a nessuno.",
        Perm.LOCATION,
    ),
    GuidePage(
        { Widget(R.drawable.widget_preview_ticket) }, "Biglietti",
        "Importa i PDF dei biglietti o inquadra il codice: li hai sempre a portata di mano, anche senza rete.",
        Perm.CAMERA,
    ),
    GuidePage(
        { Widget(R.drawable.widget_preview_subscription) }, "Abbonamenti",
        "Salva il tuo abbonamento: l'app ti ricorda la scadenza e ti avvisa di ritardi e soppressioni dei treni che usi di solito.",
    ),
    GuidePage(
        { Widget(R.drawable.widget_preview_departures) }, "Widget",
        "Aggiungi alla schermata Home i widget con partenze, treni preferiti e biglietti. Puoi anche salvare i tuoi viaggi frequenti.",
    ),
)

/** Guida al primo avvio: schede da scorrere che spiegano le funzioni e chiedono i permessi. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val pager = rememberPagerState { guidePages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == guidePages.lastIndex
    BackHandler { if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else onDone() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
        MapBackdrop(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = (1f - (pager.currentPage + pager.currentPageOffsetFraction)).coerceIn(0f, 1f)
            },
            animate = pager.currentPage == 0,
        )
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!last) TextButton(onClick = onDone) { Text("Salta") }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { i -> GuideCard(guidePages[i], ctx, i == 0) }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                repeat(guidePages.size) { i ->
                    val on = i == pager.currentPage
                    Box(
                        Modifier.padding(horizontal = 4.dp).size(if (on) 10.dp else 8.dp).clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            Button(
                onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (last) "Inizia" else "Avanti", fontSize = 16.sp) }
        }
        }
    }
}

@Composable
private fun GuideCard(p: GuidePage, ctx: android.content.Context, hero: Boolean) {
    if (hero) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.97f),
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    p.art()
                    Spacer(Modifier.height(20.dp))
                    Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        p.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { p.art() }
        Spacer(Modifier.height(16.dp))
        Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            p.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (p.perm != Perm.NONE) {
            Spacer(Modifier.height(16.dp))
            PermissionButton(p.perm, ctx)
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun isGranted(ctx: android.content.Context, perm: Perm): Boolean = when (perm) {
    Perm.NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    Perm.LOCATION -> UserLocation.granted(ctx)
    Perm.CAMERA -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    Perm.NONE -> true
}

@Composable
private fun PermissionButton(perm: Perm, ctx: android.content.Context) {
    var granted by remember { mutableStateOf(isGranted(ctx, perm)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = isGranted(ctx, perm); asked = true
    }
    val label = when (perm) {
        Perm.NOTIFICATIONS -> "Attiva le notifiche"
        Perm.LOCATION -> "Consenti la posizione"
        Perm.CAMERA -> "Consenti la fotocamera"
        Perm.NONE -> ""
    }
    if (granted) {
        Text("✓ Permesso concesso", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    } else {
        FilledTonalButton(onClick = {
            val perms = when (perm) {
                Perm.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
                Perm.LOCATION -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                Perm.CAMERA -> arrayOf(Manifest.permission.CAMERA)
                Perm.NONE -> emptyArray()
            }
            if (perms.isEmpty()) granted = true else launcher.launch(perms)
        }) { Text(label) }
        if (asked) {
            Text(
                "Puoi attivarlo più tardi dalle impostazioni di Android.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------- Immagini ----------------

/** Screenshot reale dell'app in una cornice da telefono, alta quanto lo spazio disponibile. */
@Composable
private fun Shot(res: Int) {
    Surface(
        Modifier.fillMaxHeight().aspectRatio(540f / 1102f),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 4.dp,
    ) {
        Image(painterResource(res), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** Anteprima di un widget. */
@Composable
private fun Widget(res: Int) {
    Image(
        painterResource(res), null,
        Modifier.widthIn(max = 380.dp).fillMaxWidth(0.95f).clip(RoundedCornerShape(24.dp)),
        contentScale = ContentScale.FillWidth,
    )
}

/** Logo esteso: icona dell'app e nome. */
@Composable
private fun ArtLogo() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp).clip(RoundedCornerShape(26.dp))) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(12.dp))
        Text("Capotreno", fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, color = MaterialTheme.colorScheme.primary)
    }
}

/** Mappa a tutto schermo nello stile dell'icona: verde, strade, linee che si incrociano e stazioni. */
@Composable
private fun MapBackdrop(modifier: Modifier, animate: Boolean) {
    val anim = rememberInfiniteTransition(label = "treno").animateFloat(
        0f, 1f, infiniteRepeatable(tween(70000, easing = LinearEasing)), label = "t",
    )
    val bg = MaterialTheme.colorScheme.background
    val green = MaterialTheme.colorScheme.primaryContainer
    val street = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val todo = MaterialTheme.colorScheme.outline
    val done = Color(0xFF1565C0)
    val live = Color(0xFFFF8F00)
    val regional = Color(0xFF2E7D32)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(bg)
        // parchi
        drawOval(green.copy(alpha = 0.6f), Offset(-w * 0.25f, h * 0.05f), Size(w * 0.7f, h * 0.22f))
        drawOval(green.copy(alpha = 0.5f), Offset(w * 0.55f, h * 0.68f), Size(w * 0.7f, h * 0.2f))
        drawOval(green.copy(alpha = 0.4f), Offset(w * 0.6f, -h * 0.03f), Size(w * 0.55f, h * 0.14f))
        // strade
        val sw = 7.dp.toPx()
        for (i in -1..6) {
            val x = w * i / 5f
            drawLine(street.copy(alpha = 0.4f), Offset(x, 0f), Offset(x + w * 0.22f, h), sw, StrokeCap.Round)
        }
        for (i in 0..8) {
            val y = h * i / 8f
            drawLine(street.copy(alpha = 0.3f), Offset(0f, y + h * 0.04f), Offset(w, y - h * 0.04f), sw * 0.8f, StrokeCap.Round)
        }
        // linee: i vertici condivisi sono gli incroci (stazioni di interscambio)
        class Line(val pts: List<Offset>, val color: Color, val dashed: Boolean = false)
        fun o(x: Float, y: Float) = Offset(w * x, h * y)
        val x1 = o(0.40f, 0.38f)
        val x2 = o(0.55f, 0.62f)
        val x3 = o(0.82f, 0.32f)
        val x4 = o(0.75f, 0.55f)
        val x5 = o(0.45f, 0.15f)
        val lines = listOf(
            Line(listOf(o(0f, 0.85f), o(0.28f, 0.76f), x2, x3, o(1f, 0.12f)), done),
            Line(listOf(o(0f, 0.2f), o(0.22f, 0.28f), x1, o(0.62f, 0.46f), x4, o(1f, 0.72f)), live, true),
            Line(listOf(o(0.12f, 1f), o(0.3f, 0.7f), x1, x5, o(0.58f, 0f)), todo),
            Line(listOf(o(1f, 0.96f), o(0.76f, 0.82f), x2, o(0.2f, 0.6f), o(0f, 0.5f)), done),
            Line(listOf(o(0.92f, 0f), x3, x4, o(0.9f, 0.8f), o(0.88f, 1f)), todo),
            Line(listOf(o(0f, 0.19f), o(0.22f, 0.14f), x5, o(0.7f, 0.2f), o(0.92f, 0.145f), o(1f, 0.15f)), regional),
        )
        val interchange = listOf(x1, x2, x3, x4, x5)
        lines.forEach { l ->
            val path = Path().apply {
                // Catmull-Rom: la curva passa esattamente per ogni vertice (dove sono le stazioni)
                val q = l.pts
                moveTo(q.first().x, q.first().y)
                for (i in 0 until q.size - 1) {
                    val p0 = q[(i - 1).coerceAtLeast(0)]
                    val p1 = q[i]
                    val p2 = q[i + 1]
                    val p3 = q[(i + 2).coerceAtMost(q.size - 1)]
                    cubicTo(
                        p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                        p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                        p2.x, p2.y,
                    )
                }
            }
            drawPath(
                path, l.color,
                style = Stroke(
                    width = 7.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = if (l.dashed) PathEffect.dashPathEffect(floatArrayOf(30f, 26f)) else null,
                ),
            )
        }
        // stazioni
        lines.forEach { l ->
            l.pts.drop(1).dropLast(1).filter { it !in interchange }.forEach { pt ->
                drawCircle(Color.White, 8.dp.toPx(), pt)
                drawCircle(l.color, 8.dp.toPx(), pt, style = Stroke(3.dp.toPx()))
            }
        }
        interchange.forEach { pt ->
            drawCircle(Color.White, 12.dp.toPx(), pt)
            drawCircle(ink, 12.dp.toPx(), pt, style = Stroke(4.dp.toPx()))
        }
        // treni: ogni linea ha i suoi, sempre nello stesso verso; partono da una stazione, accelerano, frenano alla successiva, sostano
        fun drawTrain(q: List<Offset>, phase: Float, color: Color) {
            val legs = q.size - 1
            val leg = phase.toInt().coerceAtMost(legs - 1)
            val tt = FastOutSlowInEasing.transform(((phase - leg - 0.15f) / 0.7f).coerceIn(0f, 1f))
            val p0 = q[(leg - 1).coerceAtLeast(0)]
            val p1 = q[leg]
            val p2 = q[leg + 1]
            val p3 = q[(leg + 2).coerceAtMost(q.size - 1)]
            val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
            val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
            val m = 1f - tt
            val pos = Offset(
                m * m * m * p1.x + 3 * m * m * tt * c1.x + 3 * m * tt * tt * c2.x + tt * tt * tt * p2.x,
                m * m * m * p1.y + 3 * m * m * tt * c1.y + 3 * m * tt * tt * c2.y + tt * tt * tt * p2.y,
            )
            val d = Offset(
                3 * m * m * (c1.x - p1.x) + 6 * m * tt * (c2.x - c1.x) + 3 * tt * tt * (p2.x - c2.x),
                3 * m * m * (c1.y - p1.y) + 6 * m * tt * (c2.y - c1.y) + 3 * tt * tt * (p2.y - c2.y),
            )
            val heading = Math.toDegrees(kotlin.math.atan2(d.y, d.x).toDouble()).toFloat()
            val r = 13.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.18f), r + 2.dp.toPx(), pos + Offset(0f, 2.dp.toPx()))
            drawCircle(color, r, pos)
            drawCircle(Color.White, r, pos, style = Stroke(3.dp.toPx()))
            rotate(heading, pos) {
                val arrow = Path().apply {
                    moveTo(pos.x + r * 0.55f, pos.y)
                    lineTo(pos.x - r * 0.4f, pos.y + r * 0.45f)
                    lineTo(pos.x - r * 0.4f, pos.y - r * 0.45f)
                    close()
                }
                drawPath(arrow, Color.White)
            }
        }
        val clock = if (animate) anim.value else 0f
        lines.forEachIndexed { idx, l ->
            val legs = l.pts.size - 1
            val cycles = kotlin.math.round(70f / (3.5f * legs)).toInt().coerceAtLeast(1)
            listOf(0f, 0.5f).forEach { off ->
                val phase = ((clock * cycles + off + idx * 0.17f) % 1f) * legs
                drawTrain(l.pts, phase, if (l.color == live) done else live)
            }
        }
    }
}
