package io.schemat.displaykit.sprite

import io.schemat.displaykit.render.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GlyphPlacementTest {

    /** A placement's absolute top, per TextMetrics.GLYPH_TOP_BEARING_PX's KDoc. */
    private fun top(p: GlyphPlacement.Placement) =
        p.row * TextMetrics.FONT_LINE_HEIGHT_PX + TextMetrics.GLYPH_TOP_BEARING_PX - p.ascent

    @Test
    fun aSixteenPxGlyphAtY30LandsWithItsTopAtExactlyY30() {
        val p = GlyphPlacement.resolve(y = 30, height = 16)
        assertEquals(GlyphPlacement.Placement(row = 3, ascent = 4), p)
        assertEquals(30, top(p!!))
    }

    @Test
    fun anEightyTwoPxGlyphAtY9LandsWithItsTopAtExactlyY9() {
        val p = GlyphPlacement.resolve(y = 9, height = 82)
        assertEquals(GlyphPlacement.Placement(row = 1, ascent = 7), p)
        assertEquals(9, top(p!!))
    }

    @Test
    fun aOnePxGlyphWhoseNaturalRowNeedsAscentSevenFallsBackToAnEarlierRow() {
        // y=9's natural row is 1 (needs ascent 7, illegal for height 1).
        val p = GlyphPlacement.resolve(y = 9, height = 1)
        assertEquals(GlyphPlacement.Placement(row = 0, ascent = -2), p)
        assertEquals(9, top(p!!), "the fallback must still land at the requested y")
    }

    @Test
    fun aOnePxGlyphAtYZeroCannotBeSatisfiedEvenAtRowZero() {
        // y=0's natural row is already 0 -- no earlier row exists to fall
        // back to, and its required ascent (7) exceeds a 1px-tall glyph.
        assertNull(GlyphPlacement.resolve(y = 0, height = 1))
    }

    @Test
    fun anyGlyphAtLeastSevenPxTallIsAlwaysSatisfiedOnItsNaturalRow() {
        for (y in 0..40) {
            val naturalRow = y / TextMetrics.FONT_LINE_HEIGHT_PX
            val p = GlyphPlacement.resolve(y, height = TextMetrics.GLYPH_TOP_BEARING_PX)
            assertEquals(naturalRow, p?.row, "y=$y should never need a row fallback at height 7")
        }
    }
}
