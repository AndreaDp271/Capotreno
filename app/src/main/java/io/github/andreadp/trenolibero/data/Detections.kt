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

/**
 * [leg]/[frac]: per le posizioni stimate, il tratto tra due fermate con coordinate note (indice della fermata di
 * partenza tra quelle note) e la frazione già percorsa, così la mappa può collocarla lungo i binari:
 * [lat]/[lon] sono sulla retta tra le due fermate, che su tratte lunghe può passare lontano dalla linea.
 */
data class LocatedDetection(
    val detection: Detection,
    val lat: Double,
    val lon: Double,
    val precision: Precision,
    val leg: Int = -1,
    val frac: Double = 0.0,
)

/** Punto alla frazione [frac] (0..1) della lunghezza di una polilinea. */
fun pointAlong(points: List<LatLon>, frac: Double): LatLon? {
    if (points.isEmpty()) return null
    if (points.size == 1) return points[0]
    val seg = (0 until points.size - 1).map { i ->
        DetectionLocator.distanceKm(points[i].lat, points[i].lon, points[i + 1].lat, points[i + 1].lon)
    }
    var left = seg.sum() * frac.coerceIn(0.0, 1.0)
    for (i in seg.indices) {
        if (left <= seg[i] || i == seg.lastIndex) {
            val t = if (seg[i] == 0.0) 0.0 else (left / seg[i]).coerceIn(0.0, 1.0)
            val a = points[i]; val b = points[i + 1]
            return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        }
        left -= seg[i]
    }
    return points.last()
}

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

/** Punto più vicino a [p] su una polilinea: indice del segmento e punto proiettato. */
fun projectOnPolyline(points: List<LatLon>, p: LatLon): Pair<Int, LatLon>? {
    if (points.size < 2) return null
    val k = kotlin.math.cos(Math.toRadians(p.lat))
    var best = Double.MAX_VALUE
    var bestI = 0
    var bestP = points.first()
    for (i in 0 until points.size - 1) {
        val a = points[i]; val b = points[i + 1]
        val ax = a.lon * k; val ay = a.lat
        val dx = b.lon * k - ax; val dy = b.lat - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.lon * k - ax) * dx + (p.lat - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val qx = ax + t * dx; val qy = ay + t * dy
        val d = (qx - p.lon * k) * (qx - p.lon * k) + (qy - p.lat) * (qy - p.lat)
        if (d < best) { best = d; bestI = i; bestP = LatLon(qy, qx / k) }
    }
    return bestI to bestP
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

        // Tratta in cui si trovava il treno all'ora del rilevamento: [leg] è l'indice della fermata appena lasciata
        // tra quelle con coordinate note (-1 se non c'è un tratto, es. prima della partenza o dopo l'arrivo)
        val located = stops.filter { it.second != null }
        val prevIdx = located.indexOfLast { (f, _) -> (f.partenzaReale ?: f.arrivoReale ?: Long.MAX_VALUE) <= d.time }
        val prev = located.getOrNull(prevIdx)
        val next = located.getOrNull(prevIdx + 1)
        val leg = if (prev != null && next != null) prevIdx else -1
        // frazione oraria del tratto già percorsa, per la stima quando il luogo non è noto
        val frac = if (prev != null && next != null) {
            val from = prev.first.partenzaReale ?: prev.first.arrivoReale ?: d.time
            val delayMs = (a.ritardo ?: 0) * 60_000L
            val to = (next.first.arrivoTeorico ?: next.first.programmata)?.plus(delayMs)
            if (to != null && to > from) ((d.time - from).toDouble() / (to - from)).coerceIn(0.1, 0.9) else 0.5
        } else 0.0
        fun at(lat: Double, lon: Double, p: Precision) = LocatedDetection(d, lat, lon, p, leg, frac)

        // 1) fermata del treno
        stops.firstOrNull { normalize(it.first.stazione) == n }?.second?.let { return at(it.lat, it.lon, Precision.EXACT) }
        // 2) stazione di transito presente in anagrafica (con più omonime, la più vicina alla tratta)
        index.filter { normalize(it.name) == n }
            .minByOrNull { c -> distanceToLeg(c, prev?.second, next?.second, located) }
            ?.let { return at(it.lat, it.lon, Precision.EXACT) }

        // 3) posto tecnico che porta il nome di una località vicina alla tratta
        val place = placeName(d.name)
        if (place.length >= 3) {
            fun strong(sn: String) = sn == place || sn.startsWith("$place ")
            // solo la prima parola in comune ("PC ROMA SMISTAMENTO" -> stazioni "ROMA …"): indizio debole, usato
            // solo se manca un nome corrispondente; altrimenti finirebbe sulla fermata appena lasciata
            fun weak(sn: String) = place.length >= 5 && sn.split(" ").firstOrNull() == place.split(" ").first()
            val strongOnes = index.filter { strong(normalize(it.name)) }
            val candidates = strongOnes.ifEmpty { index.filter { weak(normalize(it.name)) } }
            // la più vicina al tratto (non alle due fermate, che tirerebbe il punto verso di esse)
            val best = candidates.minByOrNull { c -> distanceToLeg(c, prev?.second, next?.second, located) }
            if (best != null && distanceToLeg(best, prev?.second, next?.second, located) < 30) {
                return at(best.lat, best.lon, Precision.NEAR)
            }
        }

        // 4) stima per interpolazione oraria tra le due fermate (la mappa la riporta lungo i binari del tratto)
        val p = prev?.second ?: return next?.second?.let { at(it.lat, it.lon, Precision.ESTIMATED) }
        val nx = next?.second ?: return at(p.lat, p.lon, Precision.ESTIMATED)
        return at(p.lat + (nx.lat - p.lat) * frac, p.lon + (nx.lon - p.lon) * frac, Precision.ESTIMATED)
    }

    /** Distanza (km) di una stazione dal tratto tra due fermate; senza tratto, dalla fermata nota più vicina. */
    private fun distanceToLeg(c: StationGeo, a: StationGeo?, b: StationGeo?, all: List<Pair<Fermata, StationGeo?>>): Double {
        val pts = listOfNotNull(a, b).ifEmpty { all.mapNotNull { it.second } }
        if (pts.isEmpty()) return 0.0
        if (pts.size == 1 || a == null || b == null) return pts.minOf { distanceKm(c.lat, c.lon, it.lat, it.lon) }
        val q = projectOnPolyline(listOf(LatLon(a.lat, a.lon), LatLon(b.lat, b.lon)), LatLon(c.lat, c.lon))?.second
            ?: return 0.0
        return distanceKm(c.lat, c.lon, q.lat, q.lon)
    }
}
