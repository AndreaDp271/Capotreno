package io.github.andreadp.trenolibero.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sqrt

/** I percorsi non devono contenere inversioni di marcia (svolte > 100°) negli scambi. */
class TurnCheckTest {
    private val net = File("src/main/assets/rail_graph.bin").inputStream().use { RailNetwork.load(it) }

    private fun maxTurn(p: List<LatLon>): Pair<Double, LatLon?> {
        var worst = 0.0
        var at: LatLon? = null
        for (i in 1 until p.size - 1) {
            val k = cos(Math.toRadians(p[i].lat))
            val ax = (p[i].lon - p[i - 1].lon) * k; val ay = p[i].lat - p[i - 1].lat
            val bx = (p[i + 1].lon - p[i].lon) * k; val by = p[i + 1].lat - p[i].lat
            val la = sqrt(ax * ax + ay * ay); val lb = sqrt(bx * bx + by * by)
            if (la < 1e-9 || lb < 1e-9) continue
            val ang = Math.toDegrees(acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1.0, 1.0)))
            if (ang > worst) { worst = ang; at = p[i] }
        }
        return worst to at
    }

    private fun check(a: String, pa: LatLon, b: String, pb: LatLon, profile: RailNetwork.Profile = RailNetwork.Profile.NEUTRAL) {
        val p = net.path(a, pa, b, pb, profile)!!
        val (turn, at) = maxTurn(p)
        val km = p.zipWithNext { x, y -> DetectionLocator.distanceKm(x.lat, x.lon, y.lat, y.lon) }.sum()
        println("$a -> $b: ${"%.1f".format(km)} km, svolta massima ${"%.0f".format(turn)}° presso $at")
        assertTrue("inversione di marcia ($turn°) presso $at", turn < 100)
    }

    @Test fun debugPadova() {
        val d = net.directedOnly("S02528", LatLon(45.453289, 11.891641), "S02581", LatLon(45.417846, 11.880794))
        println("diretto: ${d?.size}")
        println("staz Padova " + net.describeNear(LatLon(45.417846, 11.880794)))
        println("inversione " + net.describeNear(LatLon(45.418564, 11.87618)))
        println("vecchia inv " + net.describeNear(LatLon(45.420217, 11.871439)))
    }

    @Test fun vigodarzerePadova() = check("S02528", LatLon(45.453289, 11.891641), "S02581", LatLon(45.417846, 11.880794))
    @Test fun padovaVigodarzere() = check("S02581", LatLon(45.417846, 11.880794), "S02528", LatLon(45.453289, 11.891641))
    @Test fun milanoTorino() = check("S01700", LatLon(45.486347, 9.204528), "S00219", LatLon(45.060969, 7.67747), RailNetwork.Profile.HIGH_SPEED)
    @Test fun romaNapoli() = check("S08409", LatLon(41.901, 12.502), "S09218", LatLon(40.853, 14.272), RailNetwork.Profile.HIGH_SPEED)
}
