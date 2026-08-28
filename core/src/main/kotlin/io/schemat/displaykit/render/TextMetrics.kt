package io.schemat.displaykit.render

/**
 * Approximate metrics for Minecraft's default font, used to reason about
 * text_display geometry in world units.
 *
 * Anchoring model: a text_display renders its text block with the entity
 * origin at the BOTTOM-CENTER of the block (nametag-style — text grows
 * upward from the position). Every element that wants center-anchored text
 * must translate its display down by [verticalCenterCorrection].
 *
 * All constants live here so the anchor model is defined in exactly one place.
 */
object TextMetrics {

    /** World size of one text pixel at transformation scale 1.0. */
    const val PIXEL_SIZE = 0.025f

    /** Line height of the default font, in text pixels. */
    const val LINE_HEIGHT_PX = 10

    /**
     * The vertical pitch between consecutive lines of a **text display**, in
     * text pixels.
     *
     * This is deliberately NOT `Font.lineHeight`. Verified from the decompiled
     * 1.21.11 client, the two differ and confusing them costs one pixel of
     * drift per row:
     *
     * - `net.minecraft.client.gui.Font`'s constructor does `bipush 9; putfield
     *   lineHeight` — 9 is the font's own measure/wrap metric.
     * - `DisplayRenderer$TextDisplayRenderer.render` computes its row pitch at
     *   offset 175 as `bipush 9; iconst_1; iadd` — **9 + 1 = 10** — and uses
     *   that for both the per-line step and the block height
     *   (`lines.size() * pitch - 1`).
     *
     * Anything positional — mapping a `SpriteCanvas`/`NineSlicePainter` row
     * onto the line the client renders it as, or deriving a glyph `ascent` —
     * must use this constant. Only text measurement/wrapping uses 9, and
     * nothing in DisplayKit currently needs that.
     *
     * See `docs/superpowers/specs/2026-08-21-text-display-layout-truth.md`.
     */
    const val FONT_LINE_HEIGHT_PX = 10

    /**
     * How far below its requested canvas y a plain-text glyph actually lands,
     * relative to a sprite asked for the same y. MEASURED, not derived.
     *
     * A sprite reaches an exact canvas y through a per-glyph ascent, and that
     * is verified: probed at rendered heights 8, 16, 24 and 32, all four put
     * their top on the same pixel. Plain text has no ascent of its own -- it
     * sits on a row -- and a row's glyphs come out one full pitch lower than
     * the sprite rows they are supposed to line up with.
     *
     * Measured against flat fills, which is the one primitive whose geometry
     * could be confirmed independently: in a tab strip whose bars measure
     * exactly 24.0 canvas px tall at exactly 30px pitch, a label asked for
     * bar.y + 7 rendered its glyph top at bar.y + 16.8. Every window that put
     * a label inside chrome has been a row out because of this.
     */
    const val TEXT_ROW_ANCHOR_OFFSET_PX = FONT_LINE_HEIGHT_PX

    /**
     * [y] rounded to the nearest text ROW boundary.
     *
     * Plain text can only sit on a row: the composited canvas places it at
     * `y / FONT_LINE_HEIGHT_PX`, and unlike a sprite -- which reaches an
     * arbitrary y through a per-glyph ascent -- there is no sub-row control
     * over a vanilla glyph. So a label asked for an unaligned y is silently
     * moved, by up to a whole row.
     *
     * That is what skews every widget built from chrome PLUS text. A title
     * bar centres its label at `rect.y + (rect.h - 10) / 2`; for a 16px bar at
     * y=10 that is y=13, which lands on row 1 -- y=10 -- so the label sits
     * three pixels above where the centring asked, while the strip behind it
     * is exactly where it was put.
     *
     * Callers that want text and chrome to agree should place text here
     * FIRST, so the snap is a no-op and the drawn position is the requested
     * one.
     */
    /**
     * The smallest chrome height at or above [minHeight] that a line of text
     * can sit EXACTLY centred in.
     *
     * Callers should not have to know the grid rule. Text can only sit on a
     * row, so centring a 10px line in a 16px bar is impossible -- it wants
     * y+3 and the grid offers y+0 or y+10 -- and the label ends up hugging an
     * edge. An ODD number of rows is the one shape where centred and
     * row-aligned are the same place: one row above, one of text, one below.
     *
     * Ask for the height you want and use what comes back:
     * `val TITLE_H = TextMetrics.centringHeight(24)` gives 30.
     */
    fun centringHeight(minHeight: Int): Int {
        val pitch = FONT_LINE_HEIGHT_PX
        var rows = (minHeight + pitch - 1) / pitch
        if (rows < 1) rows = 1
        if (rows % 2 == 0) rows++          // odd rows centre exactly
        return rows * pitch
    }

    /**
     * A box's y, shifted so [contentH] of text centres in it EXACTLY.
     *
     * Text can only sit on a row [FONT_LINE_HEIGHT_PX] apart; a box can sit
     * anywhere. Centring a label inside a FIXED box therefore rounds, and the
     * rounding differs per box: three identical 24px tabs measured in-game at
     * y=44/78/112 put their labels 6, 12 and 8 pixels down, so one looked
     * centred and one was pushed through its own bottom border. Any layout
     * whose pitch is not a multiple of the row pitch has this, and no choice
     * of box height fixes it -- the boxes land on different phases.
     *
     * Moving the box onto the label's row instead makes the slack above and
     * below identical for every box, whatever y the layout hands it, and
     * costs at most half a row of drift from the requested position. It also
     * removes the reason to hunt for a box height that both tiles its sprite
     * and centres its text: the height no longer has to do both jobs.
     */
    @JvmOverloads
    fun rowCentredBoxY(boxY: Int, boxH: Int, contentH: Int = FONT_LINE_HEIGHT_PX): Int {
        require(boxH >= contentH) {
            "A $boxH px box cannot centre $contentH px of content."
        }
        val slack = (boxH - contentH) / 2
        return rowAlignedY(boxY + slack) - slack
    }

    fun rowAlignedY(y: Int): Int {
        val pitch = FONT_LINE_HEIGHT_PX
        val rounded = Math.floorDiv(y + pitch / 2, pitch) * pitch
        return rounded
    }

    /**
     * The smallest canvas height at or above [minHeight] whose rendered text
     * block is EXACTLY that tall.
     *
     * The client measures a text block as `rows * FONT_LINE_HEIGHT_PX - 1`, so
     * an arbitrary canvas height is nearly always rounded up: a 264px surface
     * emits 27 rows and measures 269. That 5px is a real gap between the
     * sprite plane and anything sized from the block -- the backing slab most
     * visibly, which then hangs below the frame.
     *
     * Rounding a surface's height to a value the block can represent exactly
     * removes the mismatch at the source rather than patching either side of
     * it, and makes "the block equals the canvas" an invariant every surface
     * can rely on -- including the two backing-slab strategies, which then
     * agree instead of having to be chosen between.
     */
    fun exactBlockHeight(minHeight: Int): Int {
        if (minHeight <= 0) return FONT_LINE_HEIGHT_PX - 1
        val rows = (minHeight + 1 + FONT_LINE_HEIGHT_PX - 1) / FONT_LINE_HEIGHT_PX
        return rows * FONT_LINE_HEIGHT_PX - 1
    }

    /**
     * The vertical bearing baked into every bitmap glyph by the client, in
     * text pixels.
     *
     * Verified from the decompiled 1.21.11 client:
     * `com.mojang.blaze3d.font.GlyphBitmap`'s defaults resolve
     * `getBearingTop()` to the provider's `ascent` field
     * (`BitmapProvider$Glyph`) and `getTop()` to `7.0f - getBearingTop()`.
     * So, relative to the origin of the line a glyph is emitted on, its top
     * sits at `GLYPH_TOP_BEARING_PX - ascent` and it occupies the vertical
     * range `[GLYPH_TOP_BEARING_PX - ascent, GLYPH_TOP_BEARING_PX - ascent +
     * height]`. Anything that derives `ascent` to place a glyph's top at a
     * specific canvas Y (see [io.schemat.displaykit.sprite.GlyphPlacement])
     * must start from this constant rather than rediscover the 7.
     */
    const val GLYPH_TOP_BEARING_PX = 7

    /**
     * Advance width of a character in the default Minecraft font (text pixels).
     * Approximation table — good enough for layout/hitbox math.
     */
    fun charWidthPx(c: Char): Int = when {
        c == 'i' || c == '!' || c == ',' || c == '.' || c == ':' ||
            c == ';' || c == '|' || c == '\'' -> 2
        c == 'l' -> 3
        c == 't' || c == 'I' || c == '[' || c == ']' || c == ' ' ||
            c == '(' || c == ')' || c == '{' || c == '}' || c == '"' -> 4
        c == 'f' || c == 'k' -> 5
        c == '@' || c == '~' -> 7
        c.code < 128 -> 6
        // Latin supplements, Greek and Cyrillic ship in the default font's
        // own nonlatin_european sheet, at the same advance as ascii.png.
        // Measuring them as unifont made a Russian line come out around two
        // and a half times its drawn width, which wrapped a two-line
        // description after thirteen characters and ellipsized the rest.
        isDefaultFontEuropean(c) -> 6
        // Everything past here really does fall back to the unifont
        // providers, whose glyphs are up to 16px wide. Guessing 6 there
        // under-estimates badly (an em-dash in a window title was enough to
        // push a padded row past lineWidth and wrap the whole surface, which
        // shifts every row below it).
        //
        // Over-estimating is the safe direction: SpriteCanvas closes the gap
        // to the next item with a spacing advance that is allowed to go
        // negative, so positioning still lands exactly, and a row that
        // measures SHORTER than predicted can never overflow.
        else -> UNICODE_FALLBACK_WIDTH_PX
    }

    /**
     * Whether [c] is drawn from the default font rather than a unifont
     * fallback: Latin-1 Supplement through Latin Extended-B, Greek, and
     * Cyrillic including its supplement.
     */
    private fun isDefaultFontEuropean(c: Char): Boolean = when (c.code) {
        in 0x00A0..0x024F -> true
        in 0x0370..0x03FF -> true
        in 0x0400..0x052F -> true
        else -> false
    }

    /** Upper bound on a unifont glyph's advance, in text pixels. */
    const val UNICODE_FALLBACK_WIDTH_PX = 16

    /** Widest line of [text], in text pixels. */
    fun textWidthPx(text: String): Int =
        text.split('\n').maxOfOrNull { line -> line.sumOf { charWidthPx(it) } } ?: 0

    /** Fit one line to [maxWidthPx], appending an ASCII ellipsis when clipped. */
    fun ellipsize(text: String, maxWidthPx: Int): String {
        require(maxWidthPx >= 0) { "Text width cannot be negative." }
        val line = text.substringBefore('\n')
        if (textWidthPx(line) <= maxWidthPx) return line
        val suffix = "..."
        val suffixWidth = textWidthPx(suffix)
        if (suffixWidth > maxWidthPx) return ""
        var used = 0
        var end = 0
        while (end < line.length) {
            val next = charWidthPx(line[end])
            if (used + next + suffixWidth > maxWidthPx) break
            used += next
            end++
        }
        return line.substring(0, end) + suffix
    }

    fun lineCount(text: String): Int = text.count { it == '\n' } + 1

    /** Rendered width of the text block in world units at [scale]. */
    fun blockWidth(text: String, scale: Float): Float =
        textWidthPx(text) * PIXEL_SIZE * scale

    /** Rendered height of the text block in world units at [scale]. */
    fun blockHeight(text: String, scale: Float): Float =
        lineCount(text) * LINE_HEIGHT_PX * PIXEL_SIZE * scale

    /**
     * Y translation that moves a bottom-anchored text block so its visual
     * center sits on the entity position.
     */
    fun verticalCenterCorrection(text: String, scale: Float): Float =
        -blockHeight(text, scale) / 2f
}
