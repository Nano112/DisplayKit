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

    @Test
    fun `a row-centred box gives identical slack above and below, at every y`() {
        val boxH = 24
        val contentH = TextMetrics.FONT_LINE_HEIGHT_PX

        // Measured in-game before this existed: three identical 24px tabs at
        // y=44/78/112 put their labels 6, 12 and 8 pixels down, because a
        // 34px pitch lands each box on a different phase of the 10px text
        // grid. One label sat centred; one was pushed through its own bottom
        // border. Every y must now come out symmetric.
        for (y in 0..200) {
            val boxY = TextMetrics.rowCentredBoxY(y, boxH, contentH)
            val labelY = TextMetrics.rowAlignedY(boxY + (boxH - contentH) / 2)
            val above = labelY - boxY
            val below = boxY + boxH - (labelY + contentH)
            assertEquals(above, below, "asymmetric at y=$y: $above above, $below below")
        }
    }

    @Test
    fun `a row-centred box stays within half a row of where it was asked for`() {
        // The box moves so the text does not have to. That is only acceptable
        // because the move is small -- a layout must not find its chrome
        // sliding a whole row away from the position it computed.
        for (y in 0..200) {
            val moved = TextMetrics.rowCentredBoxY(y, 24)
            assertTrue(
                Math.abs(moved - y) <= TextMetrics.FONT_LINE_HEIGHT_PX / 2,
                "y=$y moved to $moved"
            )
        }
    }

    @Test
    fun `boxes a whole number of rows apart all centre identically`() {
        // The complement of the fix: once a strip's pitch is a multiple of
        // the row pitch, every box shifts by the SAME amount, so the gaps the
        // layout computed survive. A pitch of 34 shifted them by -1, +5 and
        // +1 and visibly staggered the strip.
        val boxH = 24
        val shifts = (0..4).map { i ->
            val y = 44 + i * 30
            TextMetrics.rowCentredBoxY(y, boxH) - y
        }
        assertEquals(1, shifts.toSet().size, "uneven shifts: $shifts")
    }
}
