package io.github.andreadp.trenolibero.data

import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.InputStream
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.math.cos

@Serializable
data class LatLon(val lat: Double, val lon: Double)

/**
 * Rete ferroviaria italiana estratta da OpenStreetMap (© OpenStreetMap contributors, ODbL),
 * precalcolata da tools/build_rail_graph.py e inclusa nell'app: linee e percorsi
 * seguono i binari reali senza alcun servizio esterno.
 */
class RailNetwork private constructor(
    private val nodeLat: IntArray,
    private val nodeLon: IntArray,
    private val edgeA: IntArray,
    private val edgeB: IntArray,
    private val edgeLen: IntArray,
    private val edgeHs: BooleanArray,
    /** Binari di servizio (scali, tronchini): usati solo se servono, ad es. per collegare linee in stazione. */
    private val edgeService: BooleanArray,
    /** Punti intermedi dell'arco e in geoLat/geoLon[geoStart[e] until geoStart[e+1]]. */
    private val geoStart: IntArray,
    private val geoLat: IntArray,
    private val geoLon: IntArray,
    /** Archi uscenti dal nodo n: adjEdge[adjStart[n] until adjStart[n+1]]. */
    private val adjStart: IntArray,
    private val adjEdge: IntArray,
    private val stationNode: Map<String, Int>,
    /** Dimensione della componente connessa di ciascun nodo. */
    private val compSize: IntArray,
) {
    val nodeCount get() = nodeLat.size
    val edgeCount get() = edgeA.size

    /** Preferenza per le linee AV: Frecce/Italo le preferiscono, i regionali le evitano. */
    enum class Profile(val hsFactor: Double) { NEUTRAL(1.0), HIGH_SPEED(0.8), REGIONAL(4.0) }

    private val cache = ConcurrentHashMap<String, List<LatLon>>()

    private fun lat(n: Int) = nodeLat[n] / 1e6
    private fun lon(n: Int) = nodeLon[n] / 1e6
    private fun pt(n: Int) = LatLon(lat(n), lon(n))

    /** Geometria completa dell'arco, da A a B. */
    private fun edgeGeometry(e: Int): List<LatLon> = buildList {
        add(pt(edgeA[e]))
        for (j in geoStart[e] until geoStart[e + 1]) add(LatLon(geoLat[j] / 1e6, geoLon[j] / 1e6))
        add(pt(edgeB[e]))
    }

    private fun weight(e: Int, profile: Profile) =
        edgeLen[e] * (if (edgeHs[e]) profile.hsFactor else 1.0) * (if (edgeService[e]) SERVICE_FACTOR else 1.0)

    private fun lengthM(p: List<LatLon>) =
        p.zipWithNext { x, y -> DetectionLocator.distanceKm(x.lat, x.lon, y.lat, y.lon) }.sum() * 1000

    /**
     * Aggancio alla rete: un nodo (stazione agganciata in fase di generazione) oppure la proiezione
     * sul tratto di binario più vicino (bivi, posti tecnici, coordinate imprecise).
     */
    private class Snap(
        val node: Int = -1,
        val edge: Int = -1,
        /** Indice del segmento dell'arco su cui cade la proiezione. */
        val seg: Int = 0,
        val point: LatLon? = null,
        /** Punti da A alla proiezione (inclusi) e dalla proiezione a B (inclusi). */
        val toA: List<LatLon> = emptyList(),
        val toB: List<LatLon> = emptyList(),
        val costA: Double = 0.0,
        val costB: Double = 0.0,
    )

    /** Raggio di aggancio: i punti schematici di ViaggiaTreno (bivi "F…", segnaposto) possono distare parecchi km. */
    private fun snap(code: String?, p: LatLon, maxKm: Double = 20.0): Snap? {
        code?.let { stationNode[it] }?.let { return Snap(node = it) }
        val k = cos(Math.toRadians(p.lat))
        val px = p.lon * k
        val py = p.lat
        val box = maxKm / 111.0 * 1.5
        var bestD = Double.MAX_VALUE
        var bestE = -1
        var bestSeg = 0
        var bestT = 0.0
        // prima passata solo sulle reti estese, poi (se serve) anche sui frammenti isolati
        for (pass in 0..1) {
        if (bestE >= 0) break
        for (e in edgeA.indices) {
            if (pass == 0 && compSize[edgeA[e]] < MIN_COMPONENT) continue
            val g = edgeGeometry(e)
            for (i in 0 until g.size - 1) {
                val a = g[i]; val b = g[i + 1]
                // scarto rapido dei segmenti lontani
                if (minOf(a.lat, b.lat) > p.lat + box || maxOf(a.lat, b.lat) < p.lat - box) continue
                if (minOf(a.lon, b.lon) > p.lon + box / k || maxOf(a.lon, b.lon) < p.lon - box / k) continue
                val ax = a.lon * k; val ay = a.lat
                val dx = b.lon * k - ax; val dy = b.lat - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val qx = ax + t * dx - px; val qy = ay + t * dy - py
                val d = qx * qx + qy * qy
                if (d < bestD) { bestD = d; bestE = e; bestSeg = i; bestT = t }
            }
        }
        // scarta il risultato della prima passata se è oltre il raggio, per riprovare sui frammenti
        if (bestE >= 0 && pass == 0) {
            val g = edgeGeometry(bestE)
            val a = g[bestSeg]; val b = g[bestSeg + 1]
            val q = LatLon(a.lat + (b.lat - a.lat) * bestT, a.lon + (b.lon - a.lon) * bestT)
            if (DetectionLocator.distanceKm(p.lat, p.lon, q.lat, q.lon) > maxKm) { bestE = -1; bestD = Double.MAX_VALUE }
        }
        }
        if (bestE < 0) return null
        val g = edgeGeometry(bestE)
        val a = g[bestSeg]; val b = g[bestSeg + 1]
        val proj = LatLon(a.lat + (b.lat - a.lat) * bestT, a.lon + (b.lon - a.lon) * bestT)
        if (DetectionLocator.distanceKm(p.lat, p.lon, proj.lat, proj.lon) > maxKm) return null
        val toA = g.subList(0, bestSeg + 1) + proj
        val toB = listOf(proj) + g.subList(bestSeg + 1, g.size)
        val la = lengthM(toA)
        val lb = lengthM(toB)
        val scale = edgeLen[bestE] / maxOf(1.0, la + lb)
        return Snap(edge = bestE, seg = bestSeg, point = proj, toA = toA, toB = toB, costA = la * scale, costB = lb * scale)
    }

    /** Nodo del grafo per una stazione, se agganciata (usato nei test). */
    fun hasStation(code: String) = code in stationNode

    /**
     * Percorso sui binari tra due punti, null se non trovato o assurdo
     * (molto più lungo della distanza in linea d'aria: aggancio sbagliato o rete interrotta).
     */
    fun path(fromCode: String?, from: LatLon, toCode: String?, to: LatLon, profile: Profile = Profile.NEUTRAL): List<LatLon>? {
        val key = "${profile.ordinal}|${fromCode ?: from}|${toCode ?: to}"
        cache[key]?.let { return it.ifEmpty { null } }
        val result = runCatching { compute(fromCode, from, toCode, to, profile) }.getOrNull()
        cache[key] = result ?: emptyList()
        return result
    }

    private fun compute(fromCode: String?, from: LatLon, toCode: String?, to: LatLon, profile: Profile): List<LatLon>? {
        val s = snap(fromCode, from) ?: return null
        val t = snap(toCode, to) ?: return null
        return computeDirected(s, t, profile) ?: computeUnconstrained(s, t, profile)?.let(::removeSpikes)
    }

    /**
     * Toglie le andate-e-ritorni sullo stesso binario (svolte oltre 150°) rimaste nel percorso di ripiego:
     * il "dente" oltre lo scambio diventa il collegamento diretto.
     */
    private fun removeSpikes(path: List<LatLon>): List<LatLon> {
        val p = ArrayList(path)
        var changed = true
        var guard = 0
        while (changed && guard++ < 10_000) {
            changed = false
            var i = 1
            while (i < p.size - 1) {
                val k = cos(Math.toRadians(p[i].lat))
                val ax = (p[i].lon - p[i - 1].lon) * k; val ay = p[i].lat - p[i - 1].lat
                val bx = (p[i + 1].lon - p[i].lon) * k; val by = p[i + 1].lat - p[i].lat
                val la = kotlin.math.sqrt(ax * ax + ay * ay); val lb = kotlin.math.sqrt(bx * bx + by * by)
                if (la > 1e-12 && lb > 1e-12 && (ax * bx + ay * by) / (la * lb) < SPIKE_COS) {
                    p.removeAt(i); changed = true
                } else i++
            }
        }
        return p
    }

    // ---- Percorso con vincolo di svolta (niente inversioni di marcia negli scambi) ----

    /** Direzione locale (x = est scalato, y = nord) all'inizio di una polilinea, misurata su ~40 m. */
    private fun headingStart(g: List<LatLon>): DoubleArray {
        val p0 = g.first()
        var q = g.last()
        for (j in 1 until g.size) {
            q = g[j]
            if (DetectionLocator.distanceKm(p0.lat, p0.lon, q.lat, q.lon) >= 0.04) break
        }
        val k = cos(Math.toRadians(p0.lat))
        return doubleArrayOf((q.lon - p0.lon) * k, q.lat - p0.lat)
    }

    private fun headingEnd(g: List<LatLon>): DoubleArray {
        val pn = g.last()
        var q = g.first()
        for (j in g.size - 2 downTo 0) {
            q = g[j]
            if (DetectionLocator.distanceKm(pn.lat, pn.lon, q.lat, q.lon) >= 0.04) break
        }
        val k = cos(Math.toRadians(pn.lat))
        return doubleArrayOf((pn.lon - q.lon) * k, pn.lat - q.lat)
    }

    private fun turnOk(inH: DoubleArray, outH: DoubleArray): Boolean {
        val la = kotlin.math.sqrt(inH[0] * inH[0] + inH[1] * inH[1])
        val lb = kotlin.math.sqrt(outH[0] * outH[0] + outH[1] * outH[1])
        if (la < 1e-12 || lb < 1e-12) return true
        return (inH[0] * outH[0] + inH[1] * outH[1]) / (la * lb) >= MAX_TURN_COS
    }

    /** Nodi entro STATION_RADIUS_M dal nodo [n] (inclusi i binari paralleli di stazione), con la distanza in metri. */
    private fun nodesNear(n: Int): Map<Int, Double> {
        val la = lat(n); val lo = lon(n)
        val dLat = STATION_RADIUS_M / 111_000.0
        val dLon = dLat / cos(Math.toRadians(la))
        val out = HashMap<Int, Double>()
        for (m in nodeLat.indices) {
            val ml = nodeLat[m] / 1e6
            if (ml < la - dLat || ml > la + dLat) continue
            val mo = nodeLon[m] / 1e6
            if (mo < lo - dLon || mo > lo + dLon) continue
            val d = DetectionLocator.distanceKm(la, lo, ml, mo) * 1000
            if (d <= STATION_RADIUS_M) out[m] = d
        }
        return out
    }

    /** Geometria dell'arco nel verso di marcia. */
    private fun directed(e: Int, forward: Boolean) = if (forward) edgeGeometry(e) else edgeGeometry(e).reversed()

    private fun computeDirected(s: Snap, t: Snap, profile: Profile): List<LatLon>? {
        val sPt = s.point ?: pt(s.node)
        val tPt = t.point ?: pt(t.node)
        if (s.node >= 0 && s.node == t.node) return null
        if (s.edge >= 0 && s.edge == t.edge) return null // gestito dal ripiego (tratto sullo stesso arco)
        val crowKm = DetectionLocator.distanceKm(sPt.lat, sPt.lon, tPt.lat, tPt.lon)
        val hScale = minOf(1.0, profile.hsFactor)
        fun h(n: Int) = DetectionLocator.distanceKm(lat(n), lon(n), tPt.lat, tPt.lon) * 1000 * hScale
        fun w(e: Int) = weight(e, profile)

        // Stato = arco*2 + verso (0: A->B, arrivo in B; 1: B->A, arrivo in A); -1/-2 = tratti iniziali sull'arco agganciato
        fun arrival(state: Int) = when (state) {
            -1 -> edgeA[s.edge]
            -2 -> edgeB[s.edge]
            else -> if (state % 2 == 0) edgeB[state / 2] else edgeA[state / 2]
        }
        val headCache = HashMap<Int, DoubleArray>()
        fun arrivalHeading(state: Int): DoubleArray = headCache.getOrPut(state) {
            when (state) {
                -1 -> headingEnd(s.toA.reversed())
                -2 -> headingEnd(s.toB)
                else -> headingEnd(directed(state / 2, state % 2 == 0))
            }
        }

        val dist = HashMap<Int, Double>()
        val prev = HashMap<Int, Int>()
        val pq = PriorityQueue<DoubleArray>(compareBy { it[0] })
        fun push(state: Int, c: Double, from: Int?) {
            if (c < (dist[state] ?: Double.MAX_VALUE)) {
                dist[state] = c
                if (from != null) prev[state] = from else prev.remove(state)
                pq.add(doubleArrayOf(c + h(arrival(state)), state.toDouble()))
            }
        }
        // Le stazioni hanno molti binari paralleli: si parte da qualunque nodo entro STATION_RADIUS_M
        val startNodes = if (s.node >= 0) nodesNear(s.node) else emptyMap()
        if (s.node >= 0) {
            for ((sn, offset) in startNodes) {
                for (i in adjStart[sn] until adjStart[sn + 1]) {
                    val e = adjEdge[i]
                    if (edgeA[e] == edgeB[e] || e == t.edge) continue
                    push(e * 2 + if (edgeA[e] == sn) 0 else 1, offset + w(e), null)
                }
            }
        } else {
            push(-1, s.costA, null)
            push(-2, s.costB, null)
        }

        // Arrivo: al nodo, oppure a un estremo dell'arco d'arrivo proseguendo (senza invertire) fino alla proiezione
        val goalA = if (t.node < 0) edgeA[t.edge] else -1
        val goalB = if (t.node < 0) edgeB[t.edge] else -1
        val tFromA = if (t.node < 0) headingStart(t.toA) else null
        val tFromB = if (t.node < 0) headingStart(t.toB.reversed()) else null

        val goalNodes = if (t.node >= 0) nodesNear(t.node) else emptyMap()

        val maxCost = (crowKm * 3 + 30) * 1000 * maxOf(1.0, profile.hsFactor)
        var best = Double.MAX_VALUE
        var bestState = Int.MIN_VALUE
        var bestViaA = false
        var visited = 0
        while (pq.isNotEmpty()) {
            val cur = pq.poll()!!
            if (cur[0] >= best) break
            val st = cur[1].toInt()
            val dn = dist[st] ?: continue
            val n = arrival(st)
            if (cur[0] > dn + h(n) + 1e-6) continue
            val inH = arrivalHeading(st)
            if (t.node >= 0) {
                goalNodes[n]?.let { offset -> if (dn + offset < best) { best = dn + offset; bestState = st } }
            } else {
                if (n == goalA && turnOk(inH, tFromA!!) && dn + t.costA < best) { best = dn + t.costA; bestState = st; bestViaA = true }
                if (n == goalB && turnOk(inH, tFromB!!) && dn + t.costB < best) { best = dn + t.costB; bestState = st; bestViaA = false }
            }
            if (dn > maxCost || ++visited > 600_000) break
            for (i in adjStart[n] until adjStart[n + 1]) {
                val e = adjEdge[i]
                if (e == s.edge || e == t.edge || edgeA[e] == edgeB[e]) continue
                val forward = edgeA[e] == n
                val next = e * 2 + if (forward) 0 else 1
                if (st >= 0 && next / 2 == st / 2) continue // stesso arco all'indietro
                val outH = headCache.getOrPut(-1000 - next) { headingStart(directed(e, forward)) }
                if (!turnOk(inH, outH)) continue
                push(next, dn + w(e), st)
            }
        }
        if (bestState == Int.MIN_VALUE) return null

        // Ricostruzione: catena degli stati dall'arrivo alla partenza
        val chain = ArrayList<Int>()
        var st: Int? = bestState
        while (st != null) { chain.add(st); st = prev[st] }
        chain.reverse()
        val out = ArrayList<LatLon>()
        for (c in chain) {
            val g = when (c) {
                -1 -> s.toA.reversed()
                -2 -> s.toB
                else -> directed(c / 2, c % 2 == 0)
            }
            if (out.isEmpty()) out.addAll(g) else out.addAll(g.drop(1))
        }
        if (t.node < 0) out.addAll((if (bestViaA) t.toA else t.toB.reversed()).drop(1))
        if (out.size < 2) return null
        return out.takeIf { lengthM(it) / 1000 <= crowKm * 3 + 30 }
    }

    /** Ripiego: percorso sul grafo dei nodi, senza vincoli di svolta. */
    private fun computeUnconstrained(s: Snap, t: Snap, profile: Profile): List<LatLon>? {
        val sPt = s.point ?: pt(s.node)
        val tPt = t.point ?: pt(t.node)
        val crowKm = DetectionLocator.distanceKm(sPt.lat, sPt.lon, tPt.lat, tPt.lon)
        if (s.node >= 0 && s.node == t.node) return null

        // Partenza e arrivo sullo stesso arco: il tratto tra le due proiezioni
        if (s.edge >= 0 && s.edge == t.edge) {
            val g = edgeGeometry(s.edge)
            return if (s.costA <= t.costA) listOf(sPt) + g.subList(s.seg + 1, t.seg + 1) + tPt
            else listOf(sPt) + g.subList(t.seg + 1, s.seg + 1).reversed() + tPt
        }

        val hScale = minOf(1.0, profile.hsFactor)
        fun h(n: Int) = DetectionLocator.distanceKm(lat(n), lon(n), tPt.lat, tPt.lon) * 1000 * hScale

        val dist = HashMap<Int, Double>()
        val prevEdge = HashMap<Int, Int>()
        val pq = PriorityQueue<DoubleArray>(compareBy { it[0] })
        fun start(n: Int, c: Double) {
            if (c < (dist[n] ?: Double.MAX_VALUE)) { dist[n] = c; pq.add(doubleArrayOf(c + h(n), n.toDouble())) }
        }
        if (s.node >= 0) start(s.node, 0.0) else { start(edgeA[s.edge], s.costA); start(edgeB[s.edge], s.costB) }

        // Destinazioni: il nodo, oppure gli estremi dell'arco d'arrivo con il costo residuo
        val goals = HashMap<Int, Double>()
        if (t.node >= 0) goals[t.node] = 0.0 else { goals[edgeA[t.edge]] = t.costA; goals[edgeB[t.edge]] = t.costB }

        val maxCost = (crowKm * 3 + 30) * 1000 * maxOf(1.0, profile.hsFactor)
        var best = Double.MAX_VALUE
        var bestGoal = -1
        var visited = 0
        while (pq.isNotEmpty()) {
            val cur = pq.poll()!!
            if (cur[0] >= best) break
            val n = cur[1].toInt()
            val dn = dist[n] ?: continue
            if (cur[0] > dn + h(n) + 1e-6) continue
            goals[n]?.let { extra -> if (dn + extra < best) { best = dn + extra; bestGoal = n } }
            if (dn > maxCost || ++visited > 400_000) break
            for (i in adjStart[n] until adjStart[n + 1]) {
                val e = adjEdge[i]
                if (e == s.edge || e == t.edge) continue // gli archi agganciati si percorrono solo in parte
                val m = if (edgeA[e] == n) edgeB[e] else edgeA[e]
                val nd = dn + weight(e, profile)
                if (nd < (dist[m] ?: Double.MAX_VALUE)) {
                    dist[m] = nd
                    prevEdge[m] = e
                    pq.add(doubleArrayOf(nd + h(m), m.toDouble()))
                }
            }
        }
        if (bestGoal < 0) return null

        // Ricostruzione all'indietro: arrivo -> ... -> partenza, poi inversione
        val out = ArrayList<LatLon>()
        if (t.node < 0) out.addAll(if (bestGoal == edgeA[t.edge]) t.toA.reversed().dropLast(1) else t.toB.dropLast(1))
        var n = bestGoal
        out.add(pt(n))
        while (n in prevEdge) {
            val e = prevEdge[n]!!
            val forward = edgeB[e] == n
            val range = geoStart[e] until geoStart[e + 1]
            for (j in if (forward) range.reversed() else range) out.add(LatLon(geoLat[j] / 1e6, geoLon[j] / 1e6))
            n = if (forward) edgeA[e] else edgeB[e]
            out.add(pt(n))
        }
        if (s.node < 0) out.addAll(if (n == edgeA[s.edge]) s.toA.drop(1) else s.toB.reversed().drop(1))
        out.reverse()
        return out.takeIf { lengthM(it) / 1000 <= crowKm * 3 + 30 }
    }

    /** Diagnostica: solo il percorso con vincolo di svolta (null se non esiste). */
    fun directedOnly(fromCode: String?, from: LatLon, toCode: String?, to: LatLon): List<LatLon>? {
        val s = snap(fromCode, from) ?: return null
        val t = snap(toCode, to) ?: return null
        return computeDirected(s, t, Profile.NEUTRAL)
    }

    /** Diagnostica: archi che escono dal nodo più vicino a p, con direzione iniziale in gradi (0 = nord). */
    fun describeNear(p: LatLon): String {
        var best = 0; var bd = Double.MAX_VALUE
        for (n in nodeLat.indices) {
            val d = DetectionLocator.distanceKm(p.lat, p.lon, lat(n), lon(n))
            if (d < bd) { bd = d; best = n }
        }
        return buildString {
            append("nodo $best a ${"%.0f".format(bd * 1000)} m: ")
            for (i in adjStart[best] until adjStart[best + 1]) {
                val e = adjEdge[i]
                val h = headingStart(directed(e, edgeA[e] == best))
                val deg = (Math.toDegrees(kotlin.math.atan2(h[0], h[1])) + 360) % 360
                append("[arco $e ${edgeLen[e]} m dir ${"%.0f".format(deg)}° serv=${edgeService[e]}] ")
            }
        }
    }

    /** Diagnostica: distanza di aggancio (km) e dimensione della componente connessa raggiunta. */
    fun diagnose(code: String?, p: LatLon): String {
        val sn = snap(code, p, maxKm = 50.0) ?: return "nessun binario entro 50 km"
        val start = if (sn.node >= 0) sn.node else edgeA[sn.edge]
        val q = ArrayDeque<Int>().apply { add(start) }
        val seen = HashSet<Int>().apply { add(start) }
        while (q.isNotEmpty() && seen.size < 100_000) {
            val n = q.removeFirst()
            for (i in adjStart[n] until adjStart[n + 1]) {
                val e = adjEdge[i]
                val m = if (edgeA[e] == n) edgeB[e] else edgeA[e]
                if (seen.add(m)) q.add(m)
            }
        }
        val at = sn.point ?: pt(sn.node)
        return "aggancio %.2f km, componente %d nodi".format(DetectionLocator.distanceKm(p.lat, p.lon, at.lat, at.lon), seen.size)
    }

    /** Percorso di un treno: un tratto per ogni coppia di fermate consecutive (null = linea retta). */
    fun route(stops: List<Pair<String?, LatLon>>, profile: Profile): List<List<LatLon>?> =
        stops.zipWithNext { (ca, a), (cb, b) -> path(ca, a, cb, b, profile) }

    companion object {
        private const val MIN_COMPONENT = 300
        private const val SERVICE_FACTOR = 3.0
        private const val STATION_RADIUS_M = 400.0
        private val SPIKE_COS = cos(Math.toRadians(150.0))

        /** Deviazione massima ammessa in un nodo (gli scambi deviano di pochi gradi; 180° = inversione). */
        private val MAX_TURN_COS = cos(Math.toRadians(80.0))

        fun load(input: InputStream): RailNetwork {
            val d = DataInputStream(GZIPInputStream(input.buffered(1 shl 16)).buffered(1 shl 16))
            val magic = ByteArray(4).also { d.readFully(it) }
            require(String(magic) == "TLRG") { "rail_graph.bin non valido" }
            require(d.readInt() == 1) { "versione di rail_graph.bin non supportata" }

            val n = d.readInt()
            val nodeLat = IntArray(n)
            val nodeLon = IntArray(n)
            for (i in 0 until n) { nodeLat[i] = d.readInt(); nodeLon[i] = d.readInt() }

            val e = d.readInt()
            val edgeA = IntArray(e); val edgeB = IntArray(e); val edgeLen = IntArray(e)
            val edgeHs = BooleanArray(e); val edgeService = BooleanArray(e); val geoStart = IntArray(e + 1)
            var geoLat = IntArray(1 shl 20); var geoLon = IntArray(1 shl 20)
            var g = 0
            for (i in 0 until e) {
                edgeA[i] = d.readInt(); edgeB[i] = d.readInt(); edgeLen[i] = d.readInt()
                val flag = d.readByte().toInt()
                edgeHs[i] = flag and 1 != 0
                edgeService[i] = flag and 2 != 0
                val k = d.readInt()
                geoStart[i] = g
                if (g + k > geoLat.size) {
                    geoLat = geoLat.copyOf(maxOf(geoLat.size * 2, g + k))
                    geoLon = geoLon.copyOf(geoLat.size)
                }
                var pla = nodeLat[edgeA[i]]; var plo = nodeLon[edgeA[i]]
                repeat(k) {
                    pla += d.readInt(); plo += d.readInt()
                    geoLat[g] = pla; geoLon[g] = plo; g++
                }
            }
            geoStart[e] = g

            val s = d.readInt()
            val stations = HashMap<String, Int>(s * 2)
            repeat(s) { stations[d.readUTF()] = d.readInt() }

            // Liste di adiacenza compatte (CSR)
            val degree = IntArray(n + 1)
            for (i in 0 until e) { degree[edgeA[i]]++; degree[edgeB[i]]++ }
            val adjStart = IntArray(n + 1)
            for (i in 0 until n) adjStart[i + 1] = adjStart[i] + degree[i]
            val fill = adjStart.copyOf()
            val adjEdge = IntArray(adjStart[n])
            for (i in 0 until e) { adjEdge[fill[edgeA[i]]++] = i; adjEdge[fill[edgeB[i]]++] = i }

            // Componenti connesse (union-find)
            val parent = IntArray(n) { it }
            fun find(x0: Int): Int {
                var x = x0
                while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
                return x
            }
            for (i in 0 until e) {
                val ra = find(edgeA[i]); val rb = find(edgeB[i])
                if (ra != rb) parent[ra] = rb
            }
            val sizeByRoot = IntArray(n)
            for (i in 0 until n) sizeByRoot[find(i)]++
            val compSize = IntArray(n) { sizeByRoot[find(it)] }

            return RailNetwork(
                nodeLat, nodeLon, edgeA, edgeB, edgeLen, edgeHs, edgeService, geoStart,
                geoLat.copyOf(g), geoLon.copyOf(g), adjStart, adjEdge, stations, compSize,
            )
        }
    }
}
