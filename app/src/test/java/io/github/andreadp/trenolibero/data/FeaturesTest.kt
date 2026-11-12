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

    /** PDF Trenitalia con più viaggi in giorni diversi: ogni treno deve avere la propria data. */
    @Test
    fun bigliettoAndataRitornoGiorniDiversi() {
        val text = """
            VIAGGIO da Castelfranco Veneto a Padova il 10/11/2026 alle ore 09:25 PNR: AB1CDE
            Stazione di Partenza
            Castelfranco Veneto
            Ore 09:25 - 10/11/2026
            Stazione di Arrivo
            Padova
            Ore 09:53 - 10/11/2026
            Treno:  Regionale 17287
            Data Emissione 02/11/2026
            VIAGGIO da Padova a Roma Termini il 10/11/2026 alle ore 10:56 PNR: ZS7SM5
            Ore 10:56 - 10/11/2026
            Ore 14:25 - 10/11/2026
            Treno:  Frecciarossa 9413
            Data Emissione 02/11/2026
            VIAGGIO da Roma Termini a Padova il 11/11/2026 alle ore 17:35 PNR: ZS7SM5
            Ore 17:35 - 11/11/2026
            Ore 21:04 - 11/11/2026
            Treno:  Frecciarossa 9430
            SERVIZI ACCESSORI
            Treno FRECCIAROSSA 9430 Data partenza 11/11/2026
        """.trimIndent()
        val t = TicketParser.parse(text, stations + listOf("PADOVA", "CASTELFRANCO VENETO")).copy(id = "x")
        assertEquals("10/11/2026", t.date)
        assertEquals(listOf("17287", "9413", "9430"), t.trains.map { it.number })
        assertEquals(listOf("10/11/2026", "10/11/2026", "11/11/2026"), t.trains.map { t.dateOf(it) })
        assertEquals(romeMidnight("11/11/2026"), t.dateMsOf(t.trains[2]))
        assertEquals(romeMidnight("11/11/2026"), t.lastDateMs)
    }

    /** PDF Trenitalia con tre viaggi su 5 pagine (struttura reale, dati anonimi): un biglietto per viaggio. */
    @Test
    fun pdfConPiuViaggiSiDivide() {
        fun page(titolo: String, viaggio: String, body: String) =
            "CIV Carrier 1183\nData Emissione 02/11/2026\n$titolo\n$viaggio\nCONDIZIONI DI TRASPORTO\n$body\n"
        val pages = listOf(
            page("Codice Biglietto: 3000000001", "VIAGGIO da Castelfranco Veneto a Padova",
                "Ore 09:25 - 10/11/2026\nOre 09:53 - 10/11/2026\nTreno:  Regionale 17287\nNome Passeggero (Adulto)\nMario Rossi"),
            page("Numero Titolo: 3000000007", "VIAGGIO da Padova a Roma Termini il 10/11/2026 alle ore 10:56 PNR: AB1CDE",
                "Ore 10:56 - 10/11/2026\nOre 14:25 - 10/11/2026\nTreno: Frecciarossa 9413\nCarrozza:  4\nPosti: 8B, 9A, 9B\n" +
                    "Nome Passeggero (Adulto)\nLuca Bianchi\nNome Passeggero (Adulto)\nAnna Verdi"),
            page("Numero Titolo: 3000000007", "VIAGGIO da Padova a Roma Termini il 10/11/2026 alle ore 10:56 PNR: AB1CDE",
                "Treno: Frecciarossa 9413\nNome Passeggero (Adulto)\nMario Rossi\nTreno FRECCIAROSSA\n9413 10/11/2026 Padova Roma Termini"),
            page("Numero Titolo: 3000000008", "VIAGGIO da Roma Termini a Padova il 11/11/2026 alle ore 17:35 PNR: AB1CDE",
                "Ore 17:35 - 11/11/2026\nOre 21:04 - 11/11/2026\nTreno: Frecciarossa 9430\nCarrozza:  5\nPosti: 17B, 18A, 18B"),
            page("Numero Titolo: 3000000008", "VIAGGIO da Roma Termini a Padova il 11/11/2026 alle ore 17:35 PNR: AB1CDE",
                "Treno: Frecciarossa 9430"),
        )
        val trips = TicketParser.splitTrips(pages)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), trips)

        val names = stations + listOf("PADOVA", "CASTELFRANCO VENETO")
        val parsed = trips.map { g -> TicketParser.parse(g.joinToString("\n") { pages[it] }, names) }
        assertEquals(listOf("Castelfranco Veneto", "Padova", "Roma Termini"), parsed.map { it.from })
        assertEquals(listOf("Padova", "Roma Termini", "Padova"), parsed.map { it.to })
        assertEquals(listOf("10/11/2026", "10/11/2026", "11/11/2026"), parsed.map { it.date })
        assertEquals(listOf("17287", "9413", "9430"), parsed.map { it.trains.single().number })
        assertEquals(listOf("09:25", "10:56", "17:35"), parsed.map { it.departure })
        assertEquals("17B, 18A, 18B", parsed[2].seat)
        assertEquals("Luca Bianchi, Anna Verdi, Mario Rossi", parsed[1].passenger)
    }

    @Test
    fun pdfSenzaIndiziRestaUnViaggio() {
        assertEquals(listOf(listOf(0, 1)), TicketParser.splitTrips(listOf("Italo\nTreno 8901", "Condizioni")))
    }

    @Test
    fun bigliettoConCambio() {
        val t = TicketParser.parse("10.11.2026 REG 2938 Milano Centrale 08:05 IC 612 Roma Tiburtina 13:40", stations)
        assertEquals(listOf("2938", "612"), t.trains.map { it.number })
        assertEquals("10/11/2026", t.date)
        assertEquals("Roma Tiburtina", t.to)
    }

    /** Stima oraria lungo i binari: la frazione si misura sulla lunghezza del tracciato, non sulla retta. */
    @Test
    fun puntoLungoIlTracciato() {
        // tracciato a "L": 10 km verso est poi 10 km verso nord (circa)
        val a = LatLon(42.0, 12.0)
        val b = LatLon(42.0, 12.0 + 10 / (111.32 * kotlin.math.cos(Math.toRadians(42.0))))
        val c = LatLon(42.0 + 10 / 111.2, b.lon)
        val mid = pointAlong(listOf(a, b, c), 0.5)!!
        assertTrue(DetectionLocator.distanceKm(mid.lat, mid.lon, b.lat, b.lon) < 0.2)
        val q = pointAlong(listOf(a, b, c), 0.75)!!
        assertTrue(DetectionLocator.distanceKm(q.lat, q.lon, b.lat, b.lon) in 4.8..5.2)
        assertEquals(c, pointAlong(listOf(a, b, c), 1.0))
    }

    /** Rilevamenti collocati nel tratto in cui si trovava il treno a quell'ora, scegliendo l'omonima sul tratto. */
    @Test
    fun rilevamentoNelTrattoGiusto() {
        fun st(name: String, lat: Double, lon: Double) = StationGeo(name, lat = lat, lon = lon, localita = Localita(name))
        val roma = st("ROMA TERMINI", 41.901, 12.501)
        val firenze = st("FIRENZE SANTA MARIA NOVELLA", 43.776, 11.248)
        val t0 = 1_000_000_000_000L
        val stops = listOf(
            Fermata(stazione = roma.name, id = "R", partenzaReale = t0) to roma,
            Fermata(stazione = firenze.name, id = "F", arrivoTeorico = t0 + 96 * 60_000L) to firenze,
        )
        val a = Andamento(fermate = stops.map { it.first })
        val orte = st("ORTE", 42.460, 12.386)
        val orteLontana = st("ORTE", 45.0, 9.0)
        val index = listOf(roma, firenze, orteLontana, orte)

        val d = DetectionLocator.locate(Detection("ORTE", t0 + 30 * 60_000L), a, stops, index)!!
        assertEquals(Precision.EXACT, d.precision)
        assertEquals(orte.lat, d.lat, 1e-9)
        assertEquals(0, d.leg)
        assertTrue(d.frac in 0.30..0.33)

        // posto tecnico col nome della località: presso Orte, nello stesso tratto
        val b = DetectionLocator.locate(Detection("1`BIVIO ORTE SUD", t0 + 29 * 60_000L), a, stops, index)!!
        assertEquals(Precision.NEAR, b.precision)
        assertEquals(orte.lat, b.lat, 1e-9)
        assertEquals(0, b.leg)
    }

    /** Messaggio di condivisione: stato, rilevamento, arrivo alla fermata scelta e link a ViaggiaTreno. */
    @Test
    fun messaggioCondivisione() {
        val day = romeMidnight("11/11/2026")!!
        fun at(h: Int, m: Int) = day + (h * 60 + m) * 60_000L
        val roma = Fermata(stazione = "ROMA TERMINI", id = "R", partenzaTeorica = at(17, 35), partenzaReale = at(17, 37), actualFermataType = 1)
        val firenze = Fermata(stazione = "FIRENZE SANTA MARIA NOVELLA", id = "F", arrivoTeorico = at(19, 11), ritardo = -2)
        val padova = Fermata(
            stazione = "PADOVA", id = "P", arrivoTeorico = at(21, 4), ritardo = -2,
            binarioProgrammatoArrivoDescrizione = "2",
        )
        val a = Andamento(
            numeroTreno = 9430, compNumeroTreno = "FR 9430", origine = "NAPOLI CENTRALE", destinazione = "GORIZIA CENTRALE",
            ritardo = -2, stazioneUltimoRilevamento = "1`BIVIO ORVIETO NORD", oraUltimoRilevamento = at(18, 20),
            fermate = listOf(roma, firenze, padova),
        )
        val text = TrainShare.text(a, "9430", getOff = padova, dateMs = day, now = at(18, 25))
        assertEquals(
            """
            🚆 FR 9430 Napoli Centrale → Gorizia Centrale
            In anticipo di 2 min
            Prossima: Firenze Santa Maria Novella alle 19:09
            Ultimo rilevamento: 1°Bivio Orvieto Nord alle 18:20
            Arrivo a Padova previsto alle 21:02, binario 2
            Segui il treno: https://www.viaggiatreno.it/vt_pax_internet/mobile/numero?numeroTreno=9430
            """.trimIndent(),
            text,
        )
        // un altro giorno: si indica la data
        assertTrue("Viaggio del 11/11" in TrainShare.text(a, "9430", dateMs = day, now = at(18, 25) + 86_400_000L))
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

    @Test
    fun cartaFrecciaNonRendeAv() {
        val t = TicketParser.parse("ABBONAMENTO MENSILE Castelfranco Veneto - Padova. Punti CartaFRECCIA: 0. Valido dal 01/12/2026 al 31/12/2026", stations)
        assertEquals("REGIONAL", t.subscriptionType)
        val av = TicketParser.parse("Abbonamento Frecciarossa Milano - Torino. Punti CartaFRECCIA", stations)
        assertEquals("HIGH_SPEED", av.subscriptionType)
    }

    @Test
    fun trenoOmonimoSceltoDalleStazioniDelBiglietto() {
        val a = Andamento(fermate = listOf(Fermata(stazione = "ROMA TERMINI"), Fermata(stazione = "FORLI'")))
        assertTrue(servesAny(a, listOf("Forlì", "")))
        assertTrue(servesAny(a, listOf("Bologna Centrale", "Roma Termini")))
        assertTrue(!servesAny(a, listOf("Bologna Centrale")))
        assertTrue(!servesAny(a, listOf("", " ")))
    }
}
