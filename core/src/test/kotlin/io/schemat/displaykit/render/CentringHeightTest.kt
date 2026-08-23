package io.schemat.displaykit.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asking for a chrome height that can centre text must be trivial.
 *
 * The grid rule is real but it should not be a window author's problem: text
 * only sits on rows, so an even number of rows can never centre a line, and
 * a 16px bar puts its label against an edge no matter how carefully the
 * centring is written. Callers ask for the height they want and use what
 * comes back.
 */
class CentringHeightTest {

    private val pitch = TextMetrics.FONT_LINE_HEIGHT_PX

    @Test
    fun theResultIsAlwaysAnOddNumberOfRows() {
        for (want in 1..200) {
            val h = TextMetrics.centringHeight(want)
            assertEquals(0, h % pitch, "height $h for want=$want is not whole rows")
            assertTrue((h / pitch) % 2 == 1, "height $h for want=$want is an even number of rows")
        }
    }

    @Test
    fun itNeverReturnsLessThanAsked() {
        for (want in 1..200) {
            assertTrue(
                TextMetrics.centringHeight(want) >= want,
                "centringHeight($want) shrank below the request"
            )
        }
    }

    @Test
    fun aCentredLineLandsOnARowForEveryResult() {
        // The property the whole helper exists for: with a row-aligned top
        // edge, the centring expression the widgets use must come out exactly
        // on a row, so rowAlignedY is a no-op and the label is drawn where
        // the centring asked.
        for (want in 1..200) {
            val h = TextMetrics.centringHeight(want)
            for (top in listOf(0, pitch, 5 * pitch)) {
                val centred = top + (h - pitch) / 2
                assertEquals(
                    centred, TextMetrics.rowAlignedY(centred),
                    "a ${h}px bar at y=$top centres its label at $centred, which is off-grid"
                )
            }
        }
    }

    @Test
    fun theDocumentedExampleHolds() {
        assertEquals(30, TextMetrics.centringHeight(24), "24 -> 30, as the KDoc promises")
        assertEquals(10, TextMetrics.centringHeight(1))
        assertEquals(10, TextMetrics.centringHeight(10))
        assertEquals(30, TextMetrics.centringHeight(11))
        assertEquals(30, TextMetrics.centringHeight(30))
        assertEquals(50, TextMetrics.centringHeight(31))
    }

    @Test
    fun zeroAndNegativeStillGiveOneUsableRow() {
        assertEquals(pitch, TextMetrics.centringHeight(0))
        assertEquals(pitch, TextMetrics.centringHeight(-5))
    }
}
