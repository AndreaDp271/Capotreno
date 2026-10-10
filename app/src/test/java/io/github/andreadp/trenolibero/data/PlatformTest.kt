package io.github.andreadp.trenolibero.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformTest {
    @Test
    fun romanPlatformsBecomeArabic() {
        assertEquals("1", platformLabel("I"))
        assertEquals("2", platformLabel("II"))
        assertEquals("4", platformLabel("IV"))
        assertEquals("9", platformLabel("IX"))
        assertEquals("14", platformLabel("XIV"))
        assertEquals("3 TRONCO", platformLabel(" III TRONCO "))
        assertEquals("1 OVEST", platformLabel("I OVEST"))
    }

    @Test
    fun otherLabelsUnchanged() {
        assertEquals("20 BIS", platformLabel("20 BIS"))
        assertEquals("7", platformLabel("7"))
        assertEquals("C", platformLabel("C"))
        assertEquals("1 EST", platformLabel("1 EST"))
        assertEquals("IIII", platformLabel("IIII"))
        assertNull(platformLabel("  "))
        assertNull(platformLabel(null))
    }
}
