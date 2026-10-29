package io.github.andreadp.trenolibero.data

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannableString
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import io.github.andreadp.trenolibero.R

/**
 * Card delle notifiche: stessi gradienti della card in alto nella pagina del treno, un dato grande a sinistra
 * e una tessera a destra (cartello del binario, data, simbolo). Chiusa è una striscia bassa, aperta aggiunge il dettaglio.
 */
object NotificationCard {
    enum class State(val bg: Int) {
        MOVING(R.drawable.notif_hero_blue), LATE(R.drawable.notif_hero_orange), WAITING(R.drawable.notif_hero_grey),
        CANCELLED(R.drawable.notif_hero_red), DISRUPTION(R.drawable.notif_hero_red), STRIKE(R.drawable.notif_hero_purple),
        EXPIRY(R.drawable.notif_hero_teal), DIGEST(R.drawable.notif_hero_green),
    }

    enum class SideKind { SIGN, CHANGED, DATE, GLYPH }

    /** Tessera a destra: [top] è la riga piccola sopra, [num] il valore grande. */
    class Side(val kind: SideKind, val top: String, val num: String)

    /** Riga di un elenco: orario, nome e un'etichetta ([bad] in giallo, [gone] barrata). */
    class Row(val time: String, val name: String, val tag: String? = null, val bad: Boolean = false, val gone: Boolean = false)

    class Hero(
        val state: State,
        val who: String,
        val big: CharSequence,
        val det: CharSequence = "",
        val side: Side? = null,
        /** Una riga di dettaglio sotto la card, solo a notifica aperta. */
        val more: String? = null,
        /** Elenco al posto del dato grande (riepilogo partenze). */
        val rows: List<Row>? = null,
    )

    private fun applySide(v: RemoteViews, side: Side?, small: Boolean) {
        if (side == null) { v.setViewVisibility(R.id.side, View.GONE); return }
        v.setViewVisibility(R.id.side, View.VISIBLE)
        val (bg, color) = when (side.kind) {
            SideKind.SIGN -> R.drawable.notif_side_sign to 0xFFFFD54F.toInt()
            SideKind.CHANGED -> R.drawable.notif_side_changed to 0xFF263238.toInt()
            SideKind.DATE -> R.drawable.notif_side_date to 0xFF1B1D21.toInt()
            SideKind.GLYPH -> R.drawable.notif_side_glyph to Color.WHITE
        }
        v.setInt(R.id.side, "setBackgroundResource", bg)
        v.setTextColor(R.id.sideTop, color)
        v.setTextColor(R.id.sideNum, color)
        // il binario vecchio è barrato
        val top: CharSequence = if (side.kind == SideKind.CHANGED) SpannableString(side.top).apply {
            setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else side.top
        if (side.top.isBlank()) v.setViewVisibility(R.id.sideTop, View.GONE)
        else { v.setViewVisibility(R.id.sideTop, View.VISIBLE); v.setTextViewText(R.id.sideTop, top) }
        v.setTextViewText(R.id.sideNum, side.num)
        if (side.kind == SideKind.CHANGED && !small) v.setTextViewTextSize(R.id.sideNum, TypedValue.COMPLEX_UNIT_SP, 32f)
    }

    /** Notifica chiusa: una striscia bassa; per un elenco mostra il primo elemento. */
    fun small(ctx: Context, h: Hero): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.notif_hero_small)
        v.setInt(R.id.hero, "setBackgroundResource", h.state.bg)
        val first = h.rows?.firstOrNull()
        v.setTextViewText(R.id.big, first?.time ?: h.big)
        v.setTextViewText(R.id.who, h.who)
        v.setTextViewText(R.id.det, if (first != null) listOfNotNull(first.name, first.tag).joinToString(" · ") else h.det)
        applySide(v, if (first != null) null else h.side, small = true)
        return v
    }

    /** Notifica aperta: la card intera, con il dettaglio sotto. */
    fun big(ctx: Context, h: Hero): RemoteViews {
        val rows = h.rows
        if (rows != null) return list(ctx, h, rows)
        val v = RemoteViews(ctx.packageName, R.layout.notif_hero_big)
        v.setInt(R.id.hero, "setBackgroundResource", h.state.bg)
        v.setTextViewText(R.id.who, h.who)
        v.setTextViewText(R.id.big, h.big)
        // testi lunghi (es. "Scade tra 3 giorni"): un po' più piccoli per restare su una riga
        if (h.big.length > 12) v.setTextViewTextSize(R.id.big, TypedValue.COMPLEX_UNIT_SP, 22f)
        if (h.det.isBlank()) v.setViewVisibility(R.id.det, View.GONE) else v.setTextViewText(R.id.det, h.det)
        applySide(v, h.side, small = false)
        if (!h.more.isNullOrBlank()) { v.setViewVisibility(R.id.more, View.VISIBLE); v.setTextViewText(R.id.more, h.more) }
        return v
    }

    private fun list(ctx: Context, h: Hero, rows: List<Row>): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.notif_hero_list)
        v.setInt(R.id.hero, "setBackgroundResource", h.state.bg)
        v.setTextViewText(R.id.who, h.who)
        v.removeAllViews(R.id.rows)
        rows.take(4).forEach { r ->
            val row = RemoteViews(ctx.packageName, R.layout.notif_hero_row)
            val gone = { s: String -> if (r.gone) SpannableString(s).apply { setSpan(StrikethroughSpan(), 0, s.length, 0) } else s }
            row.setTextViewText(R.id.time, gone(r.time))
            row.setTextViewText(R.id.name, gone(r.name))
            if (r.tag == null) row.setViewVisibility(R.id.tag, View.GONE)
            else {
                row.setTextViewText(R.id.tag, r.tag)
                if (r.bad) { row.setInt(R.id.tag, "setBackgroundResource", R.drawable.notif_chip_warn); row.setTextColor(R.id.tag, 0xFF263238.toInt()) }
            }
            v.addView(R.id.rows, row)
        }
        return v
    }
}

/** Costruisce le card dei treni a partire dall'andamento: un dato grande per tipo di avviso. */
object TrainAlerts {
    private fun pretty(s: String?) = TrainBanner.pretty(s.orEmpty())

    private fun route(a: Andamento, destName: String? = null) = "${a.title} → ${pretty(destName ?: a.destinazione)}"

    private fun platform(f: Fermata?, arrival: Boolean): NotificationCard.Side? =
        platformLabel(
            if (arrival) f?.binarioEffettivoArrivoDescrizione ?: f?.binarioProgrammatoArrivoDescrizione
            else f?.binarioEffettivoPartenzaDescrizione ?: f?.binarioProgrammatoPartenzaDescrizione,
        )?.takeIf { it.isNotBlank() }?.let { NotificationCard.Side(NotificationCard.SideKind.SIGN, "Bin.", it) }

    /** "Arrivo ~~02:14~~ 02:22": l'orario programmato barrato e quello previsto in grassetto. */
    private fun scheduledVsExpected(label: String, sched: Long?, expected: Long?): CharSequence {
        if (expected == null) return label.trim()
        val s = SpannableStringBuilder("$label ")
        if (sched != null && kotlin.math.abs(expected - sched) >= 60_000L) {
            val a = s.length
            s.append(TrainBanner.hm(sched)).setSpan(StrikethroughSpan(), a, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.append(' ')
        }
        val b = s.length
        s.append(TrainBanner.hm(expected)).setSpan(StyleSpan(Typeface.BOLD), b, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    fun cancelled(a: Andamento, depTime: Long? = null) = NotificationCard.Hero(
        NotificationCard.State.CANCELLED, route(a), "Soppresso",
        (depTime ?: a.orarioPartenza)?.let { "Partenza ${TrainBanner.hm(it)}" } ?: "",
    )

    /** Ritardo: arancione, con l'arrivo programmato barrato e quello previsto; [destName] se il tuo viaggio finisce prima del capolinea. */
    fun delay(a: Andamento, destName: String? = null): NotificationCard.Hero {
        val stops = a.fermate
        val key = destName?.let { DetectionLocator.normalize(it) }
        val dest = (key?.let { k -> stops.firstOrNull { DetectionLocator.normalize(it.stazione) == k } } ?: stops.lastOrNull { !it.suppressed })
        val next = TrainBanner.Progress(a).next.let { stops.getOrNull(it) }
        return NotificationCard.Hero(
            NotificationCard.State.LATE, route(a, destName), "+${a.ritardo ?: 0} min",
            dest?.let { scheduledVsExpected("Arrivo", it.arrivoTeorico ?: it.programmata, TrainBanner.expected(it, a)) } ?: "",
            platform(next, next?.tipoFermata != "P"),
        )
    }

    fun platformChange(a: Andamento, stop: Fermata?, prog: String, eff: String) = NotificationCard.Hero(
        NotificationCard.State.MOVING, "${a.title} · ${pretty(stop?.stazione)}", "Cambio binario",
        stop?.let { scheduledVsExpected("Parte alle", it.partenzaTeorica ?: it.programmata, TrainBanner.expected(it, a, arrival = false)) } ?: "",
        NotificationCard.Side(NotificationCard.SideKind.CHANGED, "era $prog", eff),
    )

    /** Promemoria prima della partenza (biglietto, abbonamento, viaggio frequente). */
    fun reminder(a: Andamento, stop: Fermata?, minutes: Long, destName: String? = null, more: String? = null) = NotificationCard.Hero(
        NotificationCard.State.WAITING, route(a, destName), "Tra $minutes min",
        stop?.let { scheduledVsExpected("Parte alle", it.partenzaTeorica ?: it.programmata, TrainBanner.expected(it, a, arrival = false)) } ?: "",
        platform(stop, arrival = false), more,
    )

    fun alarm(a: Andamento, stop: Fermata, minutes: Long, arrived: Boolean) = NotificationCard.Hero(
        NotificationCard.State.MOVING, a.title,
        if (arrived) "Sei arrivato" else "Tra $minutes min",
        "${pretty(stop.stazione)} · ${TrainBanner.hm(TrainBanner.expected(stop, a))}",
        platform(stop, arrival = true),
    )

    fun departed(a: Andamento) = NotificationCard.Hero(
        NotificationCard.State.MOVING, route(a), "In viaggio",
        (a.orarioPartenza)?.let { "Partito alle ${TrainBanner.hm(it)}" } ?: "",
    )

    fun arrived(a: Andamento) = NotificationCard.Hero(
        NotificationCard.State.DIGEST, route(a), "Arrivato",
        "${pretty(a.destinazione)} · ${TrainBanner.hm(a.orarioArrivo)}",
    )

    fun notServed(a: Andamento, stopName: String) = NotificationCard.Hero(
        NotificationCard.State.CANCELLED, a.title, "Non ferma", "A ${pretty(stopName)}",
    )

    // ---- Avvisi di infomobilità e abbonamenti ----

    private val MONTHS = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")
    private val DATE_WORDS = Regex("(\\d{1,2})\\s+(${MONTHS.joinToString("|")})", RegexOption.IGNORE_CASE)

    fun strike(n: InfoNews): NotificationCard.Hero {
        val body = n.plainText()
        val m = DATE_WORDS.find(n.title) ?: DATE_WORDS.find(body)
        val more = body.takeIf { it.isNotBlank() && !it.startsWith(n.title) }?.take(220)
        return if (m != null) {
            val day = m.groupValues[1]
            val month = m.groupValues[2].lowercase()
            NotificationCard.Hero(
                NotificationCard.State.STRIKE, n.title, "$day $month", "",
                NotificationCard.Side(NotificationCard.SideKind.DATE, month.take(3).uppercase(), day), more,
            )
        } else NotificationCard.Hero(NotificationCard.State.STRIKE, "Sciopero", n.title.take(60), "", null, more)
    }

    /** "Linea Roma - Napoli: circolazione sospesa tra A e B…" → linea sopra, "Sospesa" grande, tratto sotto. */
    fun disruption(n: InfoNews): NotificationCard.Hero {
        val head = n.title.substringBefore(':').trim()
        val rest = n.title.substringAfter(':', "").trim()
        val low = n.title.lowercase()
        val big = when {
            "sospes" in low -> "Sospesa"
            "rallentat" in low -> "Rallentamenti"
            "guasto" in low -> "Guasto"
            "interrott" in low -> "Interrotta"
            else -> "Disagio"
        }
        val body = n.plainText()
        return NotificationCard.Hero(
            NotificationCard.State.DISRUPTION, head.take(60), big,
            rest.ifEmpty { head }.removePrefix("circolazione sospesa").trim().take(80),
            NotificationCard.Side(NotificationCard.SideKind.GLYPH, "", "!"),
            body.takeIf { it.isNotBlank() && !it.startsWith(n.title) }?.take(220),
        )
    }

    /** Avviso del monitor di una stazione: stazione sopra, tipo di avviso grande, testo sotto. */
    fun stationNotice(station: String, text: String): NotificationCard.Hero {
        val low = text.lowercase()
        val big = when {
            "sospes" in low || "soppress" in low -> "Sospensioni"
            "ritard" in low -> "Ritardi"
            "guasto" in low -> "Guasto"
            "sciopero" in low -> "Sciopero"
            "binario" in low -> "Binari"
            "lavori" in low -> "Lavori"
            else -> "Avviso"
        }
        val bad = big != "Avviso" && big != "Lavori" && big != "Binari"
        return NotificationCard.Hero(
            if (bad) NotificationCard.State.DISRUPTION else NotificationCard.State.WAITING,
            TrainBanner.pretty(station).take(40), big, text.take(80),
            NotificationCard.Side(NotificationCard.SideKind.GLYPH, "", "!"),
            text.takeIf { it.length > 80 },
        )
    }

    fun expiry(name: String, days: Int, validTo: String): NotificationCard.Hero {
        // validTo "dd/MM/yyyy"
        val p = validTo.split("/")
        val side = if (p.size >= 2) {
            val month = p[1].toIntOrNull()?.let { MONTHS.getOrNull(it - 1) }?.take(3)?.uppercase()
            if (month != null) NotificationCard.Side(NotificationCard.SideKind.DATE, month, p[0].trimStart('0').ifEmpty { "0" }) else null
        } else null
        return NotificationCard.Hero(
            NotificationCard.State.EXPIRY, name,
            if (days == 0) "Scade oggi" else "Scade tra $days ${if (days == 1) "giorno" else "giorni"}",
            "valido fino al ${validTo.substringBeforeLast('/', validTo)}", side,
        )
    }

    fun digest(title: String, rows: List<NotificationCard.Row>) = NotificationCard.Hero(NotificationCard.State.DIGEST, title, "", rows = rows)
}
