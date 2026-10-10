package io.github.andreadp.trenolibero.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Chiamate reali al server ViaggiaTreno. Eseguite solo con LIVE_API=1. */
class LiveApiTest {
    @Before
    fun onlyIfEnabled() = assumeTrue(!System.getenv("LIVE_API").isNullOrEmpty())

    @Test
    fun treno() = runBlocking<Unit> {
        val m = ViaggiaTrenoApi.searchTrain("9600")
        assertTrue(m.isNotEmpty())
        val a = ViaggiaTrenoApi.trainStatus(m[0].originId, m[0].number, m[0].dateMs)
        assertNotNull(a)
        assertTrue(a!!.fermate.isNotEmpty())
        println("${a.title}: ${a.origine} -> ${a.destinazione}, rit. ${a.ritardo}, ultimo ${a.stazioneUltimoRilevamento}")
    }

    @Test
    fun stazioneETabellone() = runBlocking<Unit> {
        val s = ViaggiaTrenoApi.searchStation("roma termini")
        assertTrue(s.any { it.id == "S08409" })
        val dep = ViaggiaTrenoApi.departures("S08409")
        val arr = ViaggiaTrenoApi.arrivals("S08409")
        println("Roma Termini: ${dep.size} partenze, ${arr.size} arrivi")
        assertTrue(dep.isNotEmpty() || arr.isNotEmpty())
        dep.firstOrNull()?.let { e ->
            val a = ViaggiaTrenoApi.trainStatus(e.codOrigine!!, e.numeroTreno.toString(), e.dataPartenzaTreno!!)
            assertNotNull(a)
        }
    }

    @Test
    fun geoEStatistiche() = runBlocking<Unit> {
        val d = ViaggiaTrenoApi.stationDetail("S01700")
        assertTrue(d.hasCoords)
        val st = ViaggiaTrenoApi.statistics()
        println("Treni circolanti: ${st.treniCircolanti}")
        assertNotNull(st.treniCircolanti)
        val total = (1..22).sumOf { ViaggiaTrenoApi.stationsOfRegion(it).count { s -> s.hasCoords } }
        println("Stazioni con coordinate: $total")
        assertTrue(total > 1500)
    }

    @Test
    fun lineeETreniInTransito() = runBlocking<Unit> {
        val nazionali = ViaggiaTrenoApi.lines(0)
        println("Linee nazionali: ${nazionali.size}, occupate ${nazionali.count { it.occupata }}")
        assertTrue(nazionali.size > 50)
        val busy = nazionali.first { it.occupata && it.nodoA != "S00000" }
        val dirs = ViaggiaTrenoApi.lineTrains(0, busy.trattaAB, busy.trattaBA)
        println(dirs.joinToString { "${it.tratta}: ${it.treni.size}" })
        assertTrue(dirs.sumOf { it.treni.size } > 0)
        assertTrue(ViaggiaTrenoApi.lines(1).size > 50)
    }

}
