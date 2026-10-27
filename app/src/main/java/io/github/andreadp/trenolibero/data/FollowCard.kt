package io.github.andreadp.trenolibero.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.andreadp.trenolibero.R

/**
 * Banner del treno seguito, con lo stile della timeline nella pagina del treno: da chiusa il conto alla rovescia
 * alla prossima fermata (scorre da solo), da aperta la linea delle fermate con il tratto in corso, la prossima
 * fermata evidenziata, binario e orari con il ritardo. Restituisce null quando non c'è niente da contare
 * (arrivato, soppresso): lì resta il banner semplice di [TrainBanner].
 */
object FollowCard {
    private const val PASSED = 0xFF1565C0.toInt()
    private const val TRANSIT = 0xFFFF8F00.toInt()
    private const val TODO = 0xFFB0B0B0.toInt()
    private const val LATE = 0xFFE65100.toInt()
    private const val VERY_LATE = 0xFFC62828.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    private enum class Seg { NONE, PASSED, TRANSIT, TODO }
    private enum class Dot { PASSED, HERE, NEXT, UPCOMING, TRANSIT, GAP }

    private class Row(
        val dot: Dot,
        val top: Seg,
        val bottom: Seg,
        /** Altezza in dp: fissa per tipo di riga, così la notifica aperta sta nei 256 dp concessi da Android. */
        val h: Int,
        val name: CharSequence,
        val sub: CharSequence = "",
        val tag: String? = null,
        val sign: String? = null,
        val countTo: Long? = null,
        val dim: Boolean = false,
    )

    private fun platform(f: Fermata, arrival: Boolean): String? = platformLabel(
        if (arrival) f.binarioEffettivoArrivoDescrizione ?: f.binarioProgrammatoArrivoDescrizione
        else f.binarioEffettivoPartenzaDescrizione ?: f.binarioProgrammatoPartenzaDescrizione,
    )?.takeIf { it.isNotBlank() }

    private fun delayColor(minutes: Long): Int? = when {
        minutes >= 15 -> VERY_LATE
        minutes >= 1 -> LATE
        else -> null
    }

    /** "Arr. ~~13:46~~ 13:47": programmato barrato e previsto in grassetto, colorato se in ritardo. */
    private fun times(label: String, f: Fermata, a: Andamento, arrival: Boolean): CharSequence {
        val exp = TrainBanner.expected(f, a, arrival) ?: return label
        val sched = (if (arrival) f.arrivoTeorico ?: f.partenzaTeorica else f.partenzaTeorica ?: f.arrivoTeorico) ?: f.programmata
        val b = SpannableStringBuilder("$label ")
        val late = sched?.let { (exp - it) / 60_000L } ?: 0L
        if (sched != null && late != 0L && kotlin.math.abs(exp - sched) >= 60_000L) {
            val s = b.length
            b.append(TrainBanner.hm(sched)).setSpan(StrikethroughSpan(), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.append(' ')
        }
        val s = b.length
        b.append(TrainBanner.hm(exp))
        b.setSpan(StyleSpan(android.graphics.Typeface.BOLD), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        delayColor(late)?.let { b.setSpan(ForegroundColorSpan(it), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return b
    }

    private fun delayBadge(a: Andamento): String? = when {
        a.nonPartito == true || a.ritardo == null -> null
        a.ritardo > 0 -> "+${a.ritardo}'"
        a.ritardo < 0 -> "${a.ritardo}'"
        else -> "in orario"
    }

    // ---------------- Notifica chiusa ----------------

    fun small(ctx: Context, a: Andamento, bg: Int): RemoteViews? {
        if (a.cancelled || a.arrivato == true) return null
        val p = TrainBanner.Progress(a)
        val stops = a.fermate
        val idx = if (p.standing) p.lastReached else p.next
        val arrival = !p.standing && p.lastReached >= 0
        if (idx < 0) return null
        val f = stops[idx]
        val at = TrainBanner.expected(f, a, arrival) ?: return null
        val extra = when {
            p.standing -> "Fermo · riparte alle ${TrainBanner.hm(at)}"
            p.lastReached < 0 -> "parte alle ${TrainBanner.hm(at)}"
            else -> stops.drop(idx + 1).firstOrNull { !it.suppressed }
                ?.let { "poi ${TrainBanner.pretty(it.stazione)} ${TrainBanner.hm(TrainBanner.expected(it, a))}" } ?: "capolinea"
        }
        val sub = listOfNotNull(a.title, delayBadge(a), extra).joinToString(" · ")
        return RemoteViews(ctx.packageName, R.layout.notif_follow_small).apply {
            setInt(R.id.root, "setBackgroundResource", bg)
            countdown(this, R.id.count, at, "%s")
            setTextViewText(R.id.name, TrainBanner.pretty(f.stazione))
            setTextViewText(R.id.sub, sub)
            val sign = platform(f, arrival)
            if (sign == null) setViewVisibility(R.id.sign, android.view.View.GONE) else setTextViewText(R.id.sign, sign)
        }
    }

    private fun countdown(rv: RemoteViews, id: Int, at: Long, format: String) {
        val remain = (at - System.currentTimeMillis()).coerceAtLeast(0)
        rv.setChronometerCountDown(id, true)
        // scadenza già passata (treno in ritardo sulla partenza): resta fermo a 00:00 invece di scendere sotto zero
        rv.setChronometer(id, SystemClock.elapsedRealtime() + remain, format, remain > 0)
    }

    // ---------------- Notifica aperta ----------------

    fun big(ctx: Context, a: Andamento, bg: Int, title: CharSequence): RemoteViews? {
        val rows = rows(a).takeIf { it.isNotEmpty() } ?: return null
        val seen = a.stazioneUltimoRilevamento?.takeIf { it.isNotBlank() && it != "--" && a.nonPartito != true }
            ?.let { "Rilevato a ${TrainBanner.pretty(it)} alle ${TrainBanner.hm(a.oraUltimoRilevamento)}" }
        return RemoteViews(ctx.packageName, R.layout.notif_follow_big).apply {
            setInt(R.id.head, "setBackgroundResource", bg)
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.text, seen ?: TrainBanner.status(a))
            rows.forEach { addView(R.id.rows, bind(ctx, it)) }
        }
    }

    private fun bind(ctx: Context, r: Row): RemoteViews = RemoteViews(ctx.packageName, R.layout.notif_follow_row).apply {
        setImageViewBitmap(R.id.rail, rail(ctx, r))
        setTextViewText(R.id.name, r.name)
        if (r.sub.isEmpty()) setViewVisibility(R.id.sub, android.view.View.GONE) else setTextViewText(R.id.sub, r.sub)
        if (r.tag != null) {
            setViewVisibility(R.id.tag, android.view.View.VISIBLE)
            setTextViewText(R.id.tag, r.tag)
            setInt(R.id.tag, "setBackgroundResource", if (r.dot == Dot.HERE) R.drawable.notif_tag_here else R.drawable.notif_tag_next)
        }
        r.sign?.let { setViewVisibility(R.id.sign, android.view.View.VISIBLE); setTextViewText(R.id.sign, it) }
        r.countTo?.let { setViewVisibility(R.id.count, android.view.View.VISIBLE); countdown(this, R.id.count, it, "  · tra %s") }
        when (r.dot) {
            Dot.NEXT -> setInt(R.id.root, "setBackgroundResource", R.drawable.notif_row_next)
            Dot.HERE -> setInt(R.id.root, "setBackgroundResource", R.drawable.notif_row_here)
            else -> {}
        }
        if (r.dim) setFloat(R.id.root, "setAlpha", 0.6f)
    }

    private fun rows(a: Andamento): List<Row> {
        if (a.cancelled || a.arrivato == true) return emptyList()
        val p = TrainBanner.Progress(a)
        val stops = a.fermate
        val l = p.lastReached
        val n = p.next
        if (n < 0 && !p.standing) return emptyList()
        val out = mutableListOf<Row>()
        val remaining = if (n < 0) emptyList() else (n + 1..stops.lastIndex).filter { !stops[it].suppressed }

        fun next(topSeg: Seg, tag: String): Row {
            val f = stops[n]
            val dep = l < 0
            val at = TrainBanner.expected(f, a, arrival = !dep)
            return Row(
                Dot.NEXT, topSeg, if (remaining.isEmpty()) Seg.NONE else Seg.TODO, 50,
                TrainBanner.pretty(f.stazione), times(if (dep) "Part." else "Arr.", f, a, !dep), tag,
                platform(f, !dep), at,
            )
        }

        when {
            l < 0 -> out += next(Seg.NONE, "PARTENZA")
            p.standing -> {
                val f = stops[l]
                out += Row(
                    Dot.HERE, if (l > 0) Seg.PASSED else Seg.NONE, Seg.TODO, 50, TrainBanner.pretty(f.stazione),
                    times("Riparte", f, a, false), "IL TRENO È QUI", platform(f, false),
                )
                if (n >= 0) out += next(Seg.TODO, "PROSSIMA FERMATA")
            }
            else -> {
                val f = stops[l]
                out += Row(
                    Dot.PASSED, if (l > 0) Seg.PASSED else Seg.NONE, Seg.TRANSIT, 24, TrainBanner.pretty(f.stazione),
                    TrainBanner.hm(f.partenzaReale ?: f.arrivoReale), dim = true,
                )
                out += Row(Dot.TRANSIT, Seg.TRANSIT, Seg.TRANSIT, 34, "In viaggio verso ${TrainBanner.pretty(stops[n].stazione)}")
                out += next(Seg.TRANSIT, "PROSSIMA FERMATA")
            }
        }

        val shown = if (remaining.size <= 2) remaining else listOf(remaining.first(), -1, remaining.last())
        shown.forEachIndexed { k, i ->
            val last = k == shown.lastIndex
            if (i < 0) {
                out += Row(Dot.GAP, Seg.TODO, Seg.TODO, 20, "altre ${remaining.size - 2} fermate", dim = true)
            } else {
                val f = stops[i]
                out += Row(
                    Dot.UPCOMING, Seg.TODO, if (last) Seg.NONE else Seg.TODO, 30, TrainBanner.pretty(f.stazione),
                    times("Arr.", f, a, true), sign = platform(f, true),
                )
            }
        }
        return out
    }

    // ---------------- Linea a sinistra di ogni fermata ----------------

    private fun rail(ctx: Context, r: Row): Bitmap {
        // al massimo 2x: la notifica viaggia in un Binder da 1 MB
        val s = minOf(ctx.resources.displayMetrics.density, 2f)
        val bmp = Bitmap.createBitmap((36 * s).toInt(), (r.h * s).toInt(), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val cx = bmp.width / 2f
        val cy = bmp.height / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

        fun seg(kind: Seg, y0: Float, y1: Float) {
            paint.style = Paint.Style.STROKE
            paint.pathEffect = null
            when (kind) {
                Seg.NONE -> return
                Seg.PASSED -> { paint.color = PASSED; paint.strokeWidth = 4 * s }
                Seg.TODO -> { paint.color = TODO; paint.strokeWidth = 3 * s }
                Seg.TRANSIT -> {
                    paint.color = TRANSIT and 0x00FFFFFF or 0x38000000; paint.strokeWidth = 4 * s
                    cv.drawLine(cx, y0, cx, y1, paint)
                    paint.color = TRANSIT
                    paint.pathEffect = DashPathEffect(floatArrayOf(6 * s, 5 * s), 0f)
                }
            }
            cv.drawLine(cx, y0, cx, y1, paint)
            paint.pathEffect = null
        }
        seg(r.top, 0f, cy)
        seg(r.bottom, cy, bmp.height.toFloat())

        fun halo(color: Int, radius: Float, alpha: Int) {
            paint.style = Paint.Style.FILL
            paint.color = color and 0x00FFFFFF or (alpha shl 24)
            cv.drawCircle(cx, cy, radius * s, paint)
        }
        fun train(radius: Float, color: Int) {
            paint.style = Paint.Style.FILL
            paint.color = color
            cv.drawCircle(cx, cy, radius * s, clear)
            cv.drawCircle(cx, cy, radius * s, paint)
            ContextCompat.getDrawable(ctx, R.drawable.ic_tab_train)?.mutate()?.let { d ->
                val half = (radius * 0.58f * s).toInt()
                d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                d.setTint(WHITE)
                d.draw(cv)
            }
        }
        when (r.dot) {
            Dot.PASSED -> {
                paint.style = Paint.Style.FILL; paint.color = PASSED
                cv.drawCircle(cx, cy, 7 * s, paint)
                paint.style = Paint.Style.STROKE; paint.color = WHITE; paint.strokeWidth = 1.8f * s
                paint.strokeJoin = Paint.Join.ROUND
                cv.drawLine(cx - 3 * s, cy, cx - 0.8f * s, cy + 2.5f * s, paint)
                cv.drawLine(cx - 0.8f * s, cy + 2.5f * s, cx + 3.4f * s, cy - 2.6f * s, paint)
            }
            Dot.HERE -> { halo(PASSED, 14f, 0x66); train(11f, PASSED) }
            Dot.TRANSIT -> { halo(TRANSIT, 15f, 0x66); train(11f, TRANSIT) }
            Dot.NEXT -> {
                halo(TRANSIT, 14f, 0x59)
                cv.drawCircle(cx, cy, 10 * s, clear)
                paint.style = Paint.Style.STROKE; paint.color = TRANSIT; paint.strokeWidth = 4 * s
                cv.drawCircle(cx, cy, 8 * s, paint)
            }
            Dot.UPCOMING -> {
                cv.drawCircle(cx, cy, 7 * s, clear)
                paint.style = Paint.Style.STROKE; paint.color = TODO; paint.strokeWidth = 3 * s
                cv.drawCircle(cx, cy, 5.5f * s, paint)
            }
            Dot.GAP -> {}
        }
        return bmp
    }
}
