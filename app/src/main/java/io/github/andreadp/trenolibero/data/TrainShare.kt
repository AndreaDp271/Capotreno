package io.github.andreadp.trenolibero.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Messaggio di stato di un treno da condividere (WhatsApp, SMS…): leggibile da chi non ha l'app,
 * con l'arrivo previsto alla fermata dove si scende e il link alla scheda del treno su ViaggiaTreno.
 */
object TrainShare {
    /** Scheda del treno sul sito mobile di ViaggiaTreno (con più treni omonimi chiede quale). */
    fun viaggiaTrenoUrl(number: String) =
        "https://www.viaggiatreno.it/vt_pax_internet/mobile/numero?numeroTreno=${number.filter { it.isDigit() }}"

    /** "1`BIVIO ORVIETO NORD" → "1°Bivio Orvieto Nord" */
    fun place(s: String): String =
        TrainBanner.pretty(s.trim().replace('`', '°'))
            .replace(Regex("°(\\p{Ll})")) { "°" + it.groupValues[1].uppercase(Locale.ITALY) }

    private fun day(ms: Long) = SimpleDateFormat("dd/MM", Locale.ITALY).apply { timeZone = ViaggiaTrenoApi.ROME }.format(Date(ms))

    /**
     * [getOff]: la fermata dove si scende (null: solo lo stato). [dateMs]: giorno del treno, indicato se non è oggi.
     */
    fun text(
        a: Andamento,
        number: String,
        getOff: Fermata? = null,
        dateMs: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): String = buildString {
        appendLine("🚆 ${a.title} ${place(a.origine.orEmpty())} → ${place(a.destinazione.orEmpty())}")
        if (dateMs != null && day(dateMs) != day(now)) appendLine("Viaggio del ${day(dateMs)}")
        val delay = a.ritardo
        when {
            a.cancelled -> appendLine("Treno soppresso")
            a.nonPartito == true || delay == null -> Unit
            delay > 0 -> appendLine("In ritardo di $delay min")
            delay < 0 -> appendLine("In anticipo di ${-delay} min")
            else -> appendLine("In orario")
        }
        if (!a.cancelled) {
            TrainBanner.status(a).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            val p = TrainBanner.Progress(a, now)
            val seen = a.stazioneUltimoRilevamento?.takeIf { it.isNotBlank() && it != "--" }
            if (p.running && seen != null) {
                appendLine("Ultimo rilevamento: ${place(seen)}" + (a.oraUltimoRilevamento?.let { " alle ${TrainBanner.hm(it)}" } ?: ""))
            }
            if (getOff != null) {
                val at = place(getOff.stazione)
                val platform = platformLabel(getOff.binarioEffettivoArrivoDescrizione ?: getOff.binarioProgrammatoArrivoDescrizione)
                    ?.takeIf { it.isNotBlank() }?.let { ", binario $it" }.orEmpty()
                appendLine(
                    when {
                        getOff.suppressed -> "Fermata di $at soppressa"
                        getOff.reached && getOff.arrivoReale != null -> "Arrivato a $at alle ${TrainBanner.hm(getOff.arrivoReale)}"
                        else -> "Arrivo a $at previsto alle ${TrainBanner.hm(TrainBanner.expected(getOff, a))}$platform"
                    }
                )
            }
        }
        append("Segui il treno: ${viaggiaTrenoUrl(number)}")
    }
}
