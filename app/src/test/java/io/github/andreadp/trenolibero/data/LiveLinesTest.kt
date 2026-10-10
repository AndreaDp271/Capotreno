package io.github.andreadp.trenolibero.data

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Diagnostica: linee della mappa per cui non si trova il tracciato sui binari (LIVE_API=1). */
class LiveLinesTest {
    @Test
    fun lineeSenzaTracciato() = runBlocking<Unit> {
        assumeTrue(!System.getenv("LIVE_API").isNullOrEmpty())
        val net = File("src/main/assets/rail_graph.bin").inputStream().use { RailNetwork.load(it) }
        var totalLines = 0; var totalFailed = 0; var totalRev = 0
        for (region in 0..22) {
            val lines = ViaggiaTrenoApi.lines(region)
            val failed = lines.filter { t ->
                net.path(t.nodoA, LatLon(t.latitudineA, t.longitudineA), t.nodoB, LatLon(t.latitudineB, t.longitudineB)) == null
            }
            val reversals = lines.mapNotNull { t ->
                net.path(t.nodoA, LatLon(t.latitudineA, t.longitudineA), t.nodoB, LatLon(t.latitudineB, t.longitudineB))
            }.count { p -> maxTurn(p) > 100 }
            totalLines += lines.size; totalFailed += failed.size; totalRev += reversals
            println("Regione $region: ${lines.size} linee, ${failed.size} senza tracciato, $reversals con inversioni")
            if (false) failed.take(14).forEach { t ->
                val crow = DetectionLocator.distanceKm(t.latitudineA, t.longitudineA, t.latitudineB, t.longitudineB)
                println("   ${t.nodoA} -> ${t.nodoB}  (${"%.0f".format(crow)} km)  " +
                    "A[${net.diagnose(t.nodoA, LatLon(t.latitudineA, t.longitudineA))}] " +
                    "B[${net.diagnose(t.nodoB, LatLon(t.latitudineB, t.longitudineB))}]")
            }
        }
        println("TOTALE: $totalLines linee, $totalFailed senza tracciato, $totalRev con inversioni")
    }

    private fun maxTurn(p: List<LatLon>): Double {
        var worst = 0.0
        for (i in 1 until p.size - 1) {
            val k = kotlin.math.cos(Math.toRadians(p[i].lat))
            val ax = (p[i].lon - p[i - 1].lon) * k; val ay = p[i].lat - p[i - 1].lat
            val bx = (p[i + 1].lon - p[i].lon) * k; val by = p[i + 1].lat - p[i].lat
            val la = kotlin.math.sqrt(ax * ax + ay * ay); val lb = kotlin.math.sqrt(bx * bx + by * by)
            if (la < 1e-9 || lb < 1e-9) continue
            worst = maxOf(worst, Math.toDegrees(kotlin.math.acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1.0, 1.0))))
        }
        return worst
    }
}
