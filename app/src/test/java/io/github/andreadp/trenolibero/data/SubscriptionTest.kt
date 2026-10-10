package io.github.andreadp.trenolibero.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Date

class SubscriptionTest {
    private val base = Subscription("x", "Casa-Lavoro", "S02581", "PADOVA", "S02593", "VENEZIA S.LUCIA")

    private fun leg(cat: String, company: String) = JourneyLeg(cat, "1", "A", "B", 0, 0, company)

    @Test
    fun validita() {
        val now = Subscription("", "", "", "", "", "", validFrom = "01/10/2026").fromMs!! + 5 * 86_400_000L
        val s = base.copy(validFrom = "01/10/2026", validTo = "31/10/2026")
        assertTrue(s.isActive(now))
        assertEquals(26, s.daysLeft(now))
        val scaduto = base.copy(validFrom = "01/09/2026", validTo = "30/09/2026")
        assertFalse(scaduto.isActive(now))
        assertTrue(scaduto.daysLeft(now)!! < 0)
        assertEquals("31/10/2026", Subscription.formatDate(Subscription.endAfter(s.fromMs!!, 1)))
    }

    @Test
    fun compatibilita() {
        val reg = base.copy(type = SubscriptionType.REGIONAL, operator = SubscriptionOperator.TRENITALIA)
        assertTrue(reg.accepts(Journey(listOf(leg("REG", "Trenitalia")))))
        assertTrue(reg.accepts(Journey(listOf(leg("RV", "Trenitalia TPER")))))
        assertFalse(reg.accepts(Journey(listOf(leg("FR", "Trenitalia")))))
        assertFalse(reg.accepts(Journey(listOf(leg("REG", "Trenord")))))
        val ic = base.copy(type = SubscriptionType.INTERCITY)
        assertTrue(ic.accepts(Journey(listOf(leg("IC", "Trenitalia")))))
        assertTrue(ic.accepts(Journey(listOf(leg("REG", "Trenord")))))
        val av = base.copy(type = SubscriptionType.HIGH_SPEED)
        assertTrue(av.accepts(Journey(listOf(leg("FR", "Trenitalia")))))
        assertFalse(av.accepts(Journey(listOf(leg("REG", "Trenitalia")))))
    }

    @Test
    fun treniCompatibiliDalVivo() = runBlocking<Unit> {
        assumeTrue(!System.getenv("LIVE_API").isNullOrEmpty())
        val s = base.copy(type = SubscriptionType.REGIONAL, operator = SubscriptionOperator.TRENITALIA)
        val andata = compatibleTrains(s, reverse = false, Date()).compatible
        val ritorno = compatibleTrains(s, reverse = true, Date()).compatible
        println("Padova -> Venezia: ${andata.size} regionali compatibili; ritorno: ${ritorno.size}")
        andata.take(5).forEach { println("   ${it.legs.single().title} ${it.legs.single().company} rit=${it.delay} bin=${it.platform}") }
        assertTrue(andata.all { j -> j.legs.all { it.kind == TrainKind.REGIONAL } })
        assertTrue(andata.isNotEmpty())
    }
}
