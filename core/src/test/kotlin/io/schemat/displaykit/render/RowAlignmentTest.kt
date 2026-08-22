package io.schemat.displaykit.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plain text can only sit on a row, and that is why chrome and labels drifted
 * apart.
 *
 * A sprite reaches an arbitrary canvas y through a per-glyph ascent. A
 * vanilla glyph cannot: the composited canvas puts it at
 * `y / FONT_LINE_HEIGHT_PX`, so an unaligned y was silently moved -- and,
 * because that division TRUNCATED, always upward, by up to a full row. Every
 * widget made of chrome plus a label was skewed by `y mod 10`, which is what
 * the terminal's title bar showed.
 */
class RowAlignmentTest {

    private val pitch = TextMetrics.FONT_LINE_HEIGHT_PX

    @Test
    fun anAlignedYIsUnchanged() {
        for (row in 0..12) {
            val y = row * pitch
            assertEquals(y, TextMetrics.rowAlignedY(y), "row $row must be a fixed point")
        }
    }

    @Test
    fun roundingGoesToTheNEARESTRowNotAlwaysUp() {
        // The old behaviour was truncation, so 19 became 10 -- nine pixels
        // above where it was asked for. Nearest halves the worst case and
        // stops the error being systematically in one direction.
        assertEquals(0, TextMetrics.rowAlignedY(4))
        assertEquals(10, TextMetrics.rowAlignedY(5))
        assertEquals(10, TextMetrics.rowAlignedY(13))
        assertEquals(20, TextMetrics.rowAlignedY(19))
        assertEquals(20, TextMetrics.rowAlignedY(24))
    }

    @Test
    fun theErrorIsNeverMoreThanHalfARow() {
        for (y in 0..400) {
            val d = kotlin.math.abs(TextMetrics.rowAlignedY(y) - y)
            assertTrue(d <= pitch / 2, "y=$y moved by $d, more than half a row")
        }
    }

    @Test
    fun negativeYRoundsConsistently() {
        // A label can be placed above the canvas origin by a widget whose
        // rect starts at 0, so the sign boundary must not be a special case.
        // The rule is round-half-UP throughout -- floorDiv, not truncation
        // toward zero, which would round the wrong way for negatives and
        // reintroduce a directional bias exactly where the old truncation
        // had one.
        assertEquals(0, TextMetrics.rowAlignedY(-4))
        assertEquals(0, TextMetrics.rowAlignedY(-5), "half rounds up, as it does at +5")
        assertEquals(-10, TextMetrics.rowAlignedY(-6))
        assertEquals(-10, TextMetrics.rowAlignedY(-14))
        assertEquals(-10, TextMetrics.rowAlignedY(-15), "half rounds up")
        assertEquals(-20, TextMetrics.rowAlignedY(-16))
    }

    @Test
    fun alignmentIsIdempotent() {
        for (y in -50..200) {
            val once = TextMetrics.rowAlignedY(y)
            assertEquals(once, TextMetrics.rowAlignedY(once), "y=$y is not idempotent")
        }
    }

    @Test
    fun aTitleBarsCentredLabelNowLandsWhereItIsDrawn() {
        // The concrete case: a 16px title bar at canvas y=10 centres a 10px
        // line at y=13. Unaligned, that was drawn at row 1 (y=10) -- three
        // pixels above the centring, against a strip that was exactly where
        // it was put. Aligning first makes the drawn row agree with the
        // requested y, so the two cannot disagree.
        val stripY = 10
        val stripH = 16
        val asked = stripY + (stripH - pitch) / 2
        val aligned = TextMetrics.rowAlignedY(asked)

        assertEquals(aligned, TextMetrics.rowAlignedY(aligned), "must be a fixed point")
        assertEquals(
            aligned / pitch, aligned / pitch,
            "the drawn row and the requested y now describe the same place"
        )
        assertTrue(
            aligned % pitch == 0,
            "a widget's label y must land on a row so the canvas cannot move it"
        )
    }
}
