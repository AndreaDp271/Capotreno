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

    /** Struttura del testo di un vero PDF Trenitalia (dati personali sostituiti). */
    @Test
    fun bigliettoTrenitaliaReale() {
        val text = """
            CIV Carrier 1183 -  Partita Iva: 05403151003
            Biglietto Singolo -  Issuer 1183
            Data Emissione 01/10/2026 ore 12:05
            Canale: WEB
            Numero Titolo: 1234567890
            VIAGGIO da Roma Termini a Padova il 07/10/2026 alle ore 17:30 PNR: AB1CDE
            CONDIZIONI DI TRASPORTO
            Stazione di Partenza
            Roma Termini
            Ore 17:30 - 07/10/2026
            Stazione di Arrivo
            Padova
            Ore 21:04 - 07/10/2026
            Treno:  Frecciarossa 9430
            Servizio: 2° Standard
            Carrozza:  5
            Posti: 6B
            DETTAGLIO PASSEGGERI
            Nome Passeggero (Adulto)
            Mario Rossi
            Dati di contatto comunicati
        """.trimIndent()
        val t = TicketParser.parse(text, stations + "PADOVA")
        assertEquals("07/10/2026", t.date)
        assertEquals("17:30", t.departure)
        assertEquals("21:04", t.arrival)
        assertEquals(listOf(TicketTrain("9430", "FR")), t.trains)
        assertEquals("Roma Termini", t.from)
        assertEquals("Padova", t.to)
        assertEquals("5", t.coach)
        assertEquals("6B", t.seat)
        assertEquals("AB1CDE", t.pnr)
        assertEquals("Mario Rossi", t.passenger)
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
    fun categorieAvvisi() {
        fun c(title: String, works: Boolean = false) = categorize(InfoNews(title, "", "", false), works)
        assertEquals(InfoCategory.CIRCULATION, c("Linea Bari - Taranto: circolazione fortemente rallentata dalle ore 8:00"))
        assertEquals(InfoCategory.CIRCULATION, c("CIRCOLAZIONE REGOLARE SULLA RETE ALTA VELOCITÀ"))
        assertEquals(InfoCategory.STRIKE, c("Regione Toscana: dalle ore 9:01 alle ore 17:00 di mercoledì 14 ottobre sciopero del personale"))
        assertEquals(InfoCategory.WORKS, c("INFOLAVORI LOMBARDIA", works = true))
        assertEquals(InfoCategory.OTHER, c("INFOTRENI FRECCE"))
    }

    @Test
    fun abbonamentoDaPdf() {
        val text = """
            Trenitalia - ABBONAMENTO MENSILE REGIONALE
            Da: Padova  A: Venezia S.Lucia
            Valido dal 01/10/2026 al 31/10/2026
            Titolare: Mario Rossi
        """.trimIndent()
        val t = TicketParser.parse(text, stations + listOf("PADOVA", "VENEZIA S.LUCIA"))
        assertTrue(t.isSubscription)
        assertEquals("01/10/2026", t.validFrom)
        assertEquals("31/10/2026", t.validTo)
        assertEquals("REGIONAL", t.subscriptionType)
        assertEquals("TRENITALIA", t.operator)
        assertEquals("Padova", t.from)
        assertEquals("Venezia S.lucia", t.to)

        val av = TicketParser.parse("Abbonamento Frecce Inizio validità: 05/11/2026 Fine validità: 04/12/2026", stations)
        assertEquals("05/11/2026", av.validFrom)
        assertEquals("04/12/2026", av.validTo)
        assertEquals("HIGH_SPEED", av.subscriptionType)
    }
}
