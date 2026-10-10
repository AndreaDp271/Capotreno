package io.github.andreadp.trenolibero.data

import java.text.Normalizer
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Rilevamento del treno: nome del punto (stazione, posto di comunicazione, bivio…) e ora. */
data class Detection(val name: String, val time: Long)

enum class Precision {
    /** Il punto è una stazione con coordinate note. */
    EXACT,
    /** Posto tecnico (PC, bivio…) collocato presso la località da cui prende il nome. */
    NEAR,
    /** Punto sconosciuto: posizione interpolata tra le fermate in base agli orari. */
    ESTIMATED,
}

data class LocatedDetection(val detection: Detection, val lat: Double, val lon: Double, val precision: Precision)

/** Rilevamenti visti durante la sessione, per treno (l'API restituisce solo l'ultimo). */
object DetectionHistory {
    private val history = mutableMapOf<String, MutableList<Detection>>()

    fun key(originId: String, number: String, dateMs: Long) = "$originId/$number/$dateMs"

    @Synchronized
    fun record(key: String, a: Andamento): List<Detection> {
        val list = history.getOrPut(key) { mutableListOf() }
        val name = a.stazioneUltimoRilevamento
        val time = a.oraUltimoRilevamento
        if (!name.isNullOrBlank() && name != "--" && time != null && list.none { it.name == name && it.time == time }) {
            list.add(Detection(name, time))
            list.sortBy { it.time }
        }
        return list.toList()
    }
}

object DetectionLocator {
    private val prefix = Regex(
        "^(\\d+\\s*)?(BV PC|BV|PC|PM|PP|PJ|SSE|POSTO DI COMUNICAZIONE|POSTO COMUNICAZIONE|POSTO DI MOVIMENTO|POSTO MOVIMENTO|" +
            "BIVIO|BIV|DEVIATOIO|DEV|INTERCONNESSIONE|INTERC|SCALO|SC|LIMITE|PL)\\s+"
    )
    private val suffix = Regex("\\s+(NORD|SUD|EST|OVEST|N|S|E|O|INT|INTERCONNESSIONE|BIVIO|PC|AV|LL|BIS|\\d+)$")

    fun normalize(s: String): String =
        Normalizer.normalize(s.uppercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^A-Z0-9]+"), " ")
            .trim()

    /** Toglie i prefissi tecnici: "1°BIVIO CHIUSI NORD" -> "CHIUSI", "BV/PC SETTEBAGNI" -> "SETTEBAGNI". */
    fun placeName(s: String): String {
        var n = normalize(s)
        repeat(3) { n = n.replace(prefix, "") }
        repeat(2) { n = n.replace(suffix, "") }
        return n.trim()
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = Math.PI / 180
        val a = sin((lat2 - lat1) * r / 2).pow(2) + cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).pow(2)
        return 2 * 6371 * asin(sqrt(a))
    }

    /**
     * Colloca un rilevamento sulla mappa.
     * [stops] sono le fermate del treno con le coordinate note (null se sconosciute).
     */
    fun locate(
        d: Detection,
        a: Andamento,
        stops: List<Pair<Fermata, StationGeo?>>,
        index: Collection<StationGeo>,
    ): LocatedDetection? {
        val n = normalize(d.name)
        // 1) fermata del treno
        stops.firstOrNull { normalize(it.first.stazione) == n }?.second?.let {
            return LocatedDetection(d, it.lat, it.lon, Precision.EXACT)
        }
        // 2) stazione di transito presente in anagrafica
        index.firstOrNull { normalize(it.name) == n }?.let { return LocatedDetection(d, it.lat, it.lon, Precision.EXACT) }

        // Tratta in cui si trova il treno all'ora del rilevamento
        val located = stops.filter { it.second != null }
        val prevIdx = located.indexOfLast { (f, _) -> (f.partenzaReale ?: f.arrivoReale ?: Long.MAX_VALUE) <= d.time }
        val prev = located.getOrNull(prevIdx)
        val next = located.getOrNull(prevIdx + 1)

        // 3) posto tecnico che porta il nome di una località vicina alla tratta
        val place = placeName(d.name)
        if (place.length >= 3) {
            val candidates = index.filter {
                val sn = normalize(it.name)
                sn == place || sn.startsWith("$place ") || sn.split(" ").firstOrNull() == place.split(" ").first() && place.length >= 5
            }
            val refs = listOfNotNull(prev?.second, next?.second).ifEmpty { located.mapNotNull { it.second } }
            val best = candidates.minByOrNull { c -> refs.minOfOrNull { distanceKm(c.lat, c.lon, it.lat, it.lon) } ?: 0.0 }
            if (best != null) {
                val dist = refs.minOfOrNull { distanceKm(best.lat, best.lon, it.lat, it.lon) } ?: 0.0
                if (dist < 60) return LocatedDetection(d, best.lat, best.lon, Precision.NEAR)
            }
        }

        // 4) stima per interpolazione oraria tra le due fermate
        val p = prev?.second ?: return next?.second?.let { LocatedDetection(d, it.lat, it.lon, Precision.ESTIMATED) }
        val nx = next?.second ?: return LocatedDetection(d, p.lat, p.lon, Precision.ESTIMATED)
        val from = prev.first.partenzaReale ?: prev.first.arrivoReale ?: d.time
        val delayMs = (a.ritardo ?: 0) * 60_000L
        val to = (next.first.arrivoTeorico ?: next.first.programmata)?.plus(delayMs)
        val frac = if (to != null && to > from) ((d.time - from).toDouble() / (to - from)).coerceIn(0.1, 0.9) else 0.5
        return LocatedDetection(d, p.lat + (nx.lat - p.lat) * frac, p.lon + (nx.lon - p.lon) * frac, Precision.ESTIMATED)
    }
}
