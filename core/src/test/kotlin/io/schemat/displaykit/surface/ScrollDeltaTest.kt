package io.schemat.displaykit.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrollDeltaTest {

    @Test
    fun adjacentSlotsGiveOneNotch() {
        assertEquals(1, ScrollWrap.delta(3, 4))
        assertEquals(-1, ScrollWrap.delta(4, 3))
    }

    @Test
    fun wrappingTakesTheShortWayRound() {
        assertEquals(1, ScrollWrap.delta(8, 0), "8 -> 0 is one step forward, not eight back")
        assertEquals(-1, ScrollWrap.delta(0, 8))
    }

    @Test
    fun noChangeIsZero() {
        for (i in 0..8) assertEquals(0, ScrollWrap.delta(i, i))
    }

    @Test
    fun everyPairLandsInTheRepresentableRange() {
        for (a in 0..8) for (b in 0..8) {
            val d = ScrollWrap.delta(a, b)
            assertTrue(d in -4..4, "delta($a,$b) = $d is outside -4..4")
        }
    }

    @Test
    fun fastSpinsAliasAndThatIsDocumented() {
        // Mod-9 arithmetic cannot tell +5 from -4. The shortest-distance
        // reading is the defined behaviour, not a bug to chase.
        assertEquals(-4, ScrollWrap.delta(0, 5))
    }
}
