package io.github.andreadp.trenolibero.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Date

/** Ricerca viaggio reale (LIVE_API=1). */
class LiveJourneyTest {
    private fun show(from: StationRef, to: StationRef) = runBlocking {
        val t = System.currentTimeMillis()
        val res = JourneySearch.search(from, to, Date()).journeys
        println("${from.name} -> ${to.name}: ${res.size} soluzioni in ${System.currentTimeMillis() - t} ms")
        res.take(10).forEach { j ->
            println("   ${io.github.andreadp.trenolibero.ui.fmtTime(j.departure)}-${io.github.andreadp.trenolibero.ui.fmtTime(j.arrival)} " +
                "${j.legs.joinToString(" + ") { it.title }} [${j.companies.joinToString()}] " +
                "prezzo=${j.price} rit=${j.delay} bin=${j.platform}")
        }
        res
    }

    @Test
    fun padovaRoma() {
        assumeTrue(!System.getenv("LIVE_API").isNullOrEmpty())
        assertTrue(show(StationRef("S02581", "PADOVA"), StationRef("S08409", "ROMA TERMINI")).isNotEmpty())
    }

    @Test
    fun milanoBergamoTrenord() {
        assumeTrue(!System.getenv("LIVE_API").isNullOrEmpty())
        assertTrue(show(StationRef("S01700", "MILANO CENTRALE"), StationRef("S01529", "BERGAMO")).isNotEmpty())
    }

    @Test
    fun bolognaRavennaTper() {
        assumeTrue(!System.getenv("LIVE_API").isNullOrEmpty())
        show(StationRef("S05043", "BOLOGNA CENTRALE"), StationRef("S05811", "RAVENNA"))
    }
}
