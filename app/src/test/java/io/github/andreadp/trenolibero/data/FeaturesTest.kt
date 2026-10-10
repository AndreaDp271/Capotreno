package io.github.andreadp.trenolibero.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {
    private val stations = listOf("MILANO CENTRALE", "MILANO", "ROMA TERMINI", "ROMA TIBURTINA", "CHIUSI-CHIANCIANO TERME", "SETTEBAGNI", "MELEGNANO")

    @Test
    fun bigliettoTrenitalia() {
        val text = """
            Trenitalia  BIGLIETTO / TICKET
            Stazione di Partenza / From   Stazione di Arrivo / To
            Milano Centrale   Roma Termini
            Data / Date 15/10/2026   Ora / Time 07:00   11:10
            Treno / Train Frecciarossa 1000 9515
            Carrozza / Coach 3   Posti / Seats 7A
            PNR: X7K2QZ
            Passeggero: Mario Rossi
        """.trimIndent()
        val t = TicketParser.parse(text, stations)
        assertEquals("15/10/2026", t.date)
        assertEquals(listOf(TicketTrain("9515", "FR")), t.trains)
        assertEquals("Milano Centrale", t.from)
        assertEquals("Roma Termini", t.to)
        assertEquals("07:00", t.departure)
        assertEquals("11:10", t.arrival)
        assertEquals("3", t.coach)
        assertEquals("7A", t.seat)
        assertEquals("X7K2QZ", t.pnr)
        assertNotNull(t.copy(id = "x").dateMs)
    }

    @Test
    fun bigliettoConCambio() {
        val t = TicketParser.parse("10.11.2026 REG 2938 Milano Centrale 08:05 IC 612 Roma Tiburtina 13:40", stations)
        assertEquals(listOf("2938", "612"), t.trains.map { it.number })
        assertEquals("10/11/2026", t.date)
        assertEquals("Roma Tiburtina", t.to)
    }

    @Test
    fun nomiPostiTecnici() {
        assertEquals("MELEGNANO", DetectionLocator.placeName("PC Melegnano"))
        assertEquals("SETTEBAGNI", DetectionLocator.placeName("BV/PC SETTEBAGNI"))
        assertEquals("CHIUSI", DetectionLocator.placeName("1°BIVIO CHIUSI NORD"))
        assertEquals("ORTE", DetectionLocator.placeName("1`BIVIO ORTE SUD"))
        assertEquals("CECCANO", DetectionLocator.placeName("POSTO COMUNICAZIONE CECCANO"))
        assertEquals("FORLI", DetectionLocator.normalize("FORLI'"))
    }

    @Test
    fun taglioPercorso() {
        val track = (0..100).map { LatLon(45.0, 9.0 + it * 0.01) }
        val stops = listOf(LatLon(45.0, 9.0), LatLon(45.001, 9.5), LatLon(45.0, 10.0))
        val legs = RailRouter.splitAtStops(track, stops)
        assertEquals(2, legs.size)
        assertEquals(51, legs[0]!!.size)
        assertEquals(51, legs[1]!!.size)
        assertTrue(legs[0]!!.last() == legs[1]!!.first())
    }
}
