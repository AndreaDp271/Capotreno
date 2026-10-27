package io.github.andreadp.trenolibero.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.andreadp.trenolibero.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Banner delle notifiche dei treni: la linea di tutte le fermate disegnata in una bitmap (fermate passate,
 * posizione del treno nel tratto, fermate evidenziate con orari) e le prossime fermate con binario.
 */
object TrainBanner {
    private class Colors(val fg: Int, val muted: Int, val track: Int, val accent: Int, val late: Int)

    /** La linea sta su un gradiente (come la card in alto nella pagina del treno): tutto bianco, i ritardi in ambra chiara. */
    private val ON_HERO = Colors(0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0x4DFFFFFF, 0xFFFFFFFF.toInt(), 0xFFFFE082.toInt())
    private const val HERO_BLUE = 0xFF0D47A1.toInt()

    /** Colore dell'app per le notifiche (icona e nome). */
    const val ACCENT = 0xFF0B5D3B.toInt()

    /** Dove si trova il treno: stessa logica della timeline della schermata del treno. */
    class Progress(a: Andamento, now: Long = System.currentTimeMillis()) {
        val stops = a.fermate
        val lastReached = stops.indexOfLast { it.reached && !it.suppressed }
        val running = !a.cancelled && a.nonPartito != true && a.arrivato != true && lastReached >= 0
        val standing = running && stops[lastReached].let { it.arrivoReale != null && it.partenzaReale == null } &&
            lastReached < stops.lastIndex
        val next = when {
            a.cancelled || a.arrivato == true -> -1
            lastReached < 0 -> stops.indexOfFirst { !it.suppressed }
            else -> (lastReached + 1..stops.lastIndex).firstOrNull { !stops[it].suppressed } ?: -1
        }
        private val inTransit = running && !standing && next >= 0

        /** Posizione del treno in "indici di fermata" (2.5 = a metà tra la terza e la quarta), null se non è in viaggio. */
        val position: Float? = when {
            a.cancelled || stops.isEmpty() -> null
            a.arrivato == true -> stops.lastIndex.toFloat()
            lastReached < 0 -> null
            inTransit -> {
                val from = stops[lastReached]
                val to = stops[next]
                val start = from.partenzaReale ?: from.arrivoReale
                val sched = to.arrivoTeorico ?: to.programmata
                val delayMin = to.ritardo?.takeIf { it != 0 } ?: a.ritardo ?: 0
                val f = if (start == null || sched == null) 0.5f else {
                    val end = sched + delayMin * 60_000L
                    if (end <= start) 0.5f else ((now - start).toFloat() / (end - start)).coerceIn(0.05f, 0.95f)
                }
                lastReached + f * (next - lastReached)
            }
            else -> lastReached.toFloat()
        }
    }

    /** Orario previsto a una fermata: reale se già raggiunta, altrimenti programmato più il ritardo. */
    fun expected(f: Fermata, a: Andamento, arrival: Boolean = true): Long? {
        val real = if (arrival) f.arrivoReale ?: f.partenzaReale else f.partenzaReale ?: f.arrivoReale
        if (real != null) return real
        val sched = (if (arrival) f.arrivoTeorico ?: f.partenzaTeorica else f.partenzaTeorica ?: f.arrivoTeorico) ?: f.programmata
            ?: return null
        val delay = if (a.nonPartito == true) 0 else f.ritardo?.takeIf { it != 0 } ?: a.ritardo ?: 0
        return sched + delay * 60_000L
    }

    fun hm(ms: Long?): String = ms?.let {
        SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = ViaggiaTrenoApi.ROME }.format(Date(it))
    } ?: "--:--"

    /** "MESTRE OSPEDALE" → "Mestre Ospedale" */
    fun pretty(s: String): String =
        s.lowercase(Locale.ITALY).split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ITALY) } }

    /** Stato del treno in una riga: dove si trova o quando parte. */
    fun status(a: Andamento): String {
        val p = Progress(a)
        val stops = a.fermate
        fun platform(f: Fermata, arrival: Boolean) = platformLabel(
            if (arrival) f.binarioEffettivoArrivoDescrizione ?: f.binarioProgrammatoArrivoDescrizione
            else f.binarioEffettivoPartenzaDescrizione ?: f.binarioProgrammatoPartenzaDescrizione,
        )?.takeIf { it.isNotBlank() }?.let { " · bin. $it" }.orEmpty()
        return when {
            a.cancelled -> "Treno soppresso"
            a.arrivato == true -> stops.lastOrNull()?.let { "Arrivato a ${pretty(it.stazione)} alle ${hm(expected(it, a))}" } ?: "Arrivato"
            p.lastReached < 0 -> stops.firstOrNull()?.let { "Parte alle ${hm(it.partenzaTeorica ?: it.programmata)} da ${pretty(it.stazione)}${platform(it, false)}" }
                ?: "Non ancora partito"
            p.standing -> stops[p.lastReached].let { "Fermo a ${pretty(it.stazione)} · riparte alle ${hm(expected(it, a, arrival = false))}${platform(it, false)}" }
            p.next >= 0 -> stops[p.next].let { "Prossima: ${pretty(it.stazione)} alle ${hm(expected(it, a))}${platform(it, true)}" }
            else -> a.stazioneUltimoRilevamento?.let { "Ultimo rilevamento: $it" } ?: ""
        }
    }

    /** Titolo bianco con il ritardo in un'etichetta chiara: "R 2345 → Venezia S. Lucia  +5'". */
    fun title(a: Andamento, head: String = "${a.title} → ${pretty(a.destinazione.orEmpty())}"): CharSequence {
        val b = SpannableStringBuilder(head)
        val badge = when {
            a.cancelled -> "soppresso"
            a.nonPartito == true || a.ritardo == null -> null
            a.ritardo > 0 -> "+${a.ritardo}'"
            a.ritardo < 0 -> "${a.ritardo}'"
            else -> "in orario"
        } ?: return b
        b.append("  ")
        val start = b.length
        b.append(" $badge ")
        b.setSpan(BackgroundColorSpan(0x33FFFFFF), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.setSpan(StyleSpan(Typeface.BOLD), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return b
    }

    /** Vista compatta: titolo, una riga di stato e la linea senza nomi, su gradiente [bg]. */
    fun small(ctx: Context, title: CharSequence, text: CharSequence, a: Andamento, bg: Int): RemoteViews =
        RemoteViews(ctx.packageName, R.layout.notif_train_small).apply {
            setInt(R.id.root, "setBackgroundResource", bg)
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.text, text)
            setImageViewBitmap(R.id.line, line(ctx, a, emptySet(), labels = false))
        }

    /** Vista espansa: come la compatta, con orari e nomi di alcune fermate sulla linea. */
    fun big(ctx: Context, title: CharSequence, text: CharSequence, a: Andamento, bg: Int): RemoteViews =
        RemoteViews(ctx.packageName, R.layout.notif_train_big).apply {
            setInt(R.id.root, "setBackgroundResource", bg)
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.text, text)
            setImageViewBitmap(R.id.line, line(ctx, a, emptySet(), labels = true))
        }

    /**
     * La linea delle fermate. Con [labels] sopra ai pallini delle fermate principali c'è l'orario e sotto il nome:
     * si danno in ordine di importanza (prossima, evidenziate, capolinea, poi le altre vicine al treno) finché c'è spazio.
     */
    fun line(ctx: Context, a: Andamento, highlight: Set<String>, labels: Boolean): Bitmap {
        val c = ON_HERO
        // al massimo 2x: la notifica viaggia in un Binder da 1 MB
        val s = minOf(ctx.resources.displayMetrics.density, 2f)
        val wDp = (ctx.resources.configuration.screenWidthDp - 64).coerceIn(240, 340)
        val hDp = if (labels) 80 else 36
        val bmp = Bitmap.createBitmap((wDp * s).toInt(), (hDp * s).toInt(), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val stops = a.fermate
        if (stops.isEmpty()) return bmp
        val p = Progress(a)
        val markerR = (if (labels) 11f else 10f) * s
        val padX = markerR + s
        val y = if (labels) 36f * s else bmp.height / 2f
        val span = bmp.width - 2 * padX
        fun x(i: Float) = padX + if (stops.size == 1) span / 2 else span * i / stops.lastIndex

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        val thick = (if (labels) 5f else 4.5f) * s
        // binario: grigio intero, poi colorato fino al treno
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thick
        paint.color = c.track
        cv.drawLine(x(0f), y, x(stops.lastIndex.toFloat()), y, paint)
        val pos = p.position
        if (pos != null && pos > 0f) {
            paint.color = c.accent
            cv.drawLine(x(0f), y, x(pos), y, paint)
        }

        val dotR = (if (labels) 4.2f else 3.4f) * s
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        stops.forEachIndexed { i, f ->
            val cx = x(i.toFloat())
            val passed = pos != null && i <= pos
            val hl = f.id in highlight
            val r = if (hl || i == 0 || i == stops.lastIndex) dotR * 1.45f else dotR
            when {
                f.suppressed -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f * s; paint.color = c.late
                    val d = r * 0.9f
                    cv.drawCircle(cx, y, r + s, clear)
                    cv.drawLine(cx - d, y - d, cx + d, y + d, paint)
                    cv.drawLine(cx - d, y + d, cx + d, y - d, paint)
                }
                passed -> {
                    paint.style = Paint.Style.FILL; paint.color = c.accent
                    cv.drawCircle(cx, y, r, paint)
                }
                else -> {
                    // pallino vuoto: si "buca" la bitmap così si vede lo sfondo della notifica
                    cv.drawCircle(cx, y, r, clear)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f * s
                    paint.color = if (hl) c.accent else c.muted
                    cv.drawCircle(cx, y, r - 0.9f * s, paint)
                }
            }
            if (hl) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.4f * s; paint.color = c.accent
                cv.drawCircle(cx, y, r + 2.6f * s, paint)
            }
        }

        if (labels) drawLabels(ctx, cv, a, p, highlight, c, s, y, ::x)

        // il treno
        val mPos = pos ?: if (!a.cancelled) 0f else null
        if (mPos != null) {
            val mx = x(mPos)
            paint.style = Paint.Style.FILL
            paint.color = c.accent
            paint.alpha = if (pos == null) 110 else 255
            cv.drawCircle(mx, y, markerR, clear)
            cv.drawCircle(mx, y, markerR, paint)
            paint.alpha = 255
            ContextCompat.getDrawable(ctx, R.drawable.ic_tab_train)?.mutate()?.let { d ->
                val half = (markerR * 0.62f).toInt()
                d.setBounds((mx - half).toInt(), (y - half).toInt(), (mx + half).toInt(), (y + half).toInt())
                d.setTint(HERO_BLUE)
                d.draw(cv)
            }
        }
        return bmp
    }

    private fun drawLabels(
        ctx: Context, cv: Canvas, a: Andamento, p: Progress, highlight: Set<String>, c: Colors, s: Float, y: Float, x: (Float) -> Float,
    ) {
        val stops = a.fermate
        val focus = if (p.standing) p.lastReached else if (p.next >= 0) p.next else if (a.arrivato == true) stops.lastIndex else 0
        val order = buildList {
            add(focus)
            stops.indices.filter { stops[it].id in highlight }.forEach { add(it) }
            add(0); add(stops.lastIndex)
            stops.indices.sortedBy { kotlin.math.abs(it - focus) }.forEach { add(it) }
        }.distinct().filter { !stops[it].suppressed }

        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * s; color = c.fg }
        val time = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * s; typeface = Typeface.DEFAULT_BOLD }
        val gap = 8f * s
        val maxW = 104f * s
        val taken = mutableListOf<RectF>()
        val w = cv.width.toFloat()
        for (i in order) {
            val f = stops[i]
            val hl = f.id in highlight
            name.typeface = if (hl || i == focus) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            name.color = if (hl) c.accent else c.fg
            val label = TextUtils.ellipsize(pretty(f.stazione), name, maxW, TextUtils.TruncateAt.END).toString()
            val lw = name.measureText(label)
            val left = (x(i.toFloat()) - lw / 2).coerceIn(0f, w - lw)
            val box = RectF(left - gap / 2, 0f, left + lw + gap / 2, 1f)
            if (taken.any { RectF.intersects(it, box) }) continue
            taken += box
            cv.drawText(label, left, y + 29f * s, name)

            val exp = expected(f, a, arrival = i != 0)
            val sched = if (i == 0) f.partenzaTeorica ?: f.programmata else f.arrivoTeorico ?: f.programmata
            time.color = when {
                f.reached -> c.muted
                exp != null && sched != null && exp - sched >= 60_000L -> c.late
                else -> c.fg
            }
            val t = hm(exp)
            val tw = time.measureText(t)
            cv.drawText(t, (x(i.toFloat()) - tw / 2).coerceIn(0f, w - tw), y - 16f * s, time)
        }
    }
}
