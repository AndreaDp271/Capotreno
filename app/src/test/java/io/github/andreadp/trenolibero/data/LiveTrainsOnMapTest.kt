package io.github.andreadp.trenolibero.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Treni in transito con posizione e tratto fino alla prossima fermata (LIVE_API=1). */
class LiveTrainsOnMapTest {
    @Test
    fun treniSulleLinee() = runBlocking<Unit> {
        assumeTrue(!System.getenv("LIVE_API").isNullOrEmpty())
        val net = File("src/main/assets/rail_graph.bin").inputStream().use { RailNetwork.load(it) }
        val index = (1..22).flatMap { ViaggiaTrenoApi.stationsOfRegion(it) }.filter { it.hasCoords }.associateBy { it.codiceStazione }
        val lines = ViaggiaTrenoApi.lines(20).map { 20 to it } // Veneto
        val t0 = System.currentTimeMillis()
        val trains = TrainsOnMap.load(lines, index, net)
        println("Veneto: ${lines.count { it.second.occupata }} linee occupate, ${trains.size} treni in ${System.currentTimeMillis() - t0} ms")
        trains.take(8).forEach {
            val km = it.segment.zipWithNext { a, b -> DetectionLocator.distanceKm(a.lat, a.lon, b.lat, b.lon) }.sum()
            println("  ${it.title} → ${it.destination}: rilevato ${it.detection.detection.name} (${it.detection.precision}), " +
                "prossima ${it.nextStop}, tratto ${"%.1f".format(km)} km")
        }
        assertTrue(trains.isNotEmpty())
    }
}
