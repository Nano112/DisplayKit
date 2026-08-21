package io.schemat.displaykit.sprite

import io.schemat.displaykit.render.TextMetrics

/**
 * Resolves where a bitmap glyph must be emitted (its text-component row) and
 * what font-provider `ascent` it needs, so that a glyph [Placement.ascent]
 * pixels tall lands with its TOP at an absolute canvas pixel `y`.
 *
 * The client computes a glyph's top, relative to its line's origin, as
 * `TextMetrics.GLYPH_TOP_BEARING_PX - ascent` (see that constant's KDoc for
 * the decompiled source it comes from). Line `r`'s origin sits at
 * `r * TextMetrics.FONT_LINE_HEIGHT_PX`, so placing a glyph emitted on row
 * `r` with its top at absolute `y` requires:
 *
 *     ascent = r * FONT_LINE_HEIGHT_PX + GLYPH_TOP_BEARING_PX - y
 *
 * Starting from `y`'s natural row (`y / FONT_LINE_HEIGHT_PX`), the required
 * ascent is at most `GLYPH_TOP_BEARING_PX` (7) — satisfiable by any glyph at
 * least that tall. Shorter glyphs (nine-slice crops can be as small as 1px)
 * sometimes cannot: the client enforces `ascent <= height`
 * (`"Ascent {} higher than height {}"`, which fails the WHOLE font file if
 * ever violated). Each step to an earlier row subtracts `FONT_LINE_HEIGHT_PX`
 * from the required ascent, making it easier to satisfy, so [resolve] walks
 * rows backward from the natural one, clamping at row 0, until the
 * requirement holds.
 */
object GlyphPlacement {

    /** A legal placement: emit the glyph on text-component [row] with the given [ascent]. */
    data class Placement(val row: Int, val ascent: Int)

    /**
     * Finds a legal (row, ascent) pair for a glyph [height] pixels tall whose
     * top must land at absolute canvas pixel [y].
     *
     * Returns null when even row 0 cannot satisfy `ascent <= height` — only
     * possible for a very short glyph at a very small [y]. Callers must warn
     * once and skip the draw rather than hand an illegal ascent to a font
     * provider, which would fail the whole pack rather than lose one glyph.
     */
    fun resolve(y: Int, height: Int): Placement? {
        var row = y / TextMetrics.FONT_LINE_HEIGHT_PX
        while (true) {
            val ascent = row * TextMetrics.FONT_LINE_HEIGHT_PX + TextMetrics.GLYPH_TOP_BEARING_PX - y
            if (ascent <= height) return Placement(row, ascent)
            if (row == 0) return null
            row--
        }
    }
}
