package io.github.andreadp.trenolibero.data

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing di risposte reali dell'API salvate come fixture. */
class ParsingTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val json = ViaggiaTrenoApi.json

    @Test
    fun andamentoTreno() {
        val a = json.decodeFromString(Andamento.serializer(), res("andamento.json"))
        assertEquals(9600, a.numeroTreno)
        assertEquals("FR 9600", a.title)
        assertEquals("MILANO CENTRALE", a.origine)
        assertEquals("TORINO PORTA NUOVA", a.destinazione)
        assertTrue(a.fermate.size >= 3)
        val first = a.fermate.first()
        assertEquals("P", first.tipoFermata)
        assertEquals("6", first.binarioProgrammatoPartenzaDescrizione)
        assertEquals("7", first.binarioEffettivoPartenzaDescrizione)
        assertNotNull(first.partenzaTeorica)
        assertEquals("A", a.fermate.last().tipoFermata)
        assertEquals("TORINO PORTA NUOVA", a.stazioneUltimoRilevamento)
    }

    @Test
    fun tabellone() {
        val ser = ListSerializer(BoardEntry.serializer())
        val p = json.decodeFromString(ser, res("partenze.json"))
        assertTrue(p.isNotEmpty())
        assertTrue(p.all { it.numeroTreno > 0 && it.orarioPartenza != null && it.dataPartenzaTreno != null })
        val a = json.decodeFromString(ser, res("arrivi.json"))
        assertTrue(a.all { it.orarioArrivo != null && it.codOrigine != null })
    }

    @Test
    fun stazioni() {
        val list = json.decodeFromString(ListSerializer(StationGeo.serializer()), res("elenco_lombardia.json"))
        assertTrue(list.size > 300)
        val d = json.decodeFromString(StationGeo.serializer(), res("dettaglio.json"))
        assertEquals("MILANO CENTRALE", d.name)
        assertTrue(d.hasCoords)
    }
}
