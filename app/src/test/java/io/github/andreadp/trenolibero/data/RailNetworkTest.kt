package io.github.andreadp.trenolibero.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Percorsi sulla rete OSM inclusa negli asset (app/src/main/assets/rail_graph.bin). */
class RailNetworkTest {
    companion object {
        lateinit var net: RailNetwork

        @BeforeClass
        @JvmStatic
        fun load() {
            val t = System.currentTimeMillis()
            net = File("src/main/assets/rail_graph.bin").inputStream().use { RailNetwork.load(it) }
            println("Grafo: ${net.nodeCount} nodi, ${net.edgeCount} archi, caricato in ${System.currentTimeMillis() - t} ms")
        }

        fun km(p: List<LatLon>) = p.zipWithNext { a, b -> DetectionLocator.distanceKm(a.lat, a.lon, b.lat, b.lon) }.sum()
    }

    private fun check(from: String, a: LatLon, to: String, b: LatLon, profile: RailNetwork.Profile, min: Double, max: Double) {
        val t = System.currentTimeMillis()
        val p = net.path(from, a, to, b, profile)
        assertNotNull("$from -> $to non trovato", p)
        val len = km(p!!)
        println("$from -> $to ($profile): ${"%.1f".format(len)} km, ${p.size} punti, ${System.currentTimeMillis() - t} ms")
        assertTrue("$from -> $to lunghezza $len fuori da [$min, $max]", len in min..max)
    }

    @Test
    fun milanoTorinoAltaVelocita() = check(
        "S01700", LatLon(45.486347, 9.204528), "S00219", LatLon(45.060969, 7.67747),
        RailNetwork.Profile.HIGH_SPEED, 135.0, 165.0,
    )

    @Test
    fun romaNapoliAltaVelocita() = check(
        "S08409", LatLon(41.901, 12.502), "S09218", LatLon(40.853, 14.272),
        RailNetwork.Profile.HIGH_SPEED, 200.0, 240.0,
    )

    @Test
    fun torinoGenovaRegionale() = check(
        "S00219", LatLon(45.060969, 7.67747), "S04702", LatLon(44.406, 8.946),
        RailNetwork.Profile.REGIONAL, 150.0, 185.0,
    )

    @Test
    fun stazioniVicine() = check(
        "S01700", LatLon(45.486347, 9.204528), "S01701", LatLon(45.484, 9.237),
        RailNetwork.Profile.NEUTRAL, 2.0, 8.0,
    )
}
