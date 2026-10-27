package io.github.andreadp.trenolibero.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceKeyTest {
    @Test
    fun usesFirstWordForDistinctivePlaces() {
        assertEquals("PADOVA", placeKey("PADOVA"))
        assertEquals("CASTELFRANCO", placeKey("CASTELFRANCO VENETO"))
    }

    /** "Castello" da solo farebbe scattare gli avvisi di Città di Castello (Umbria) sulla stazione veneta. */
    @Test
    fun usesFullNameForGenericFirstWords() {
        assertEquals("CASTELLO DI GODEGO", placeKey("CASTELLO DI GODEGO"))
        assertEquals("PORTO VIRO", placeKey("PORTO VIRO"))
    }
}
