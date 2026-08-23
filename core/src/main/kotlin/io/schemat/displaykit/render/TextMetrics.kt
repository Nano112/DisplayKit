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
        // Non-ASCII does not come from ascii.png at all -- the client falls
        // back to the unifont providers, whose glyphs are up to 16px wide.
        // Guessing 6 here under-estimates badly (an em-dash in a window title
        // was enough to push a padded row past lineWidth and wrap the whole
        // surface, which shifts every row below it).
        //
        // Over-estimating is the safe direction: SpriteCanvas closes the gap
        // to the next item with a spacing advance that is allowed to go
        // negative, so positioning still lands exactly, and a row that
        // measures SHORTER than predicted can never overflow.
        else -> UNICODE_FALLBACK_WIDTH_PX
    }

    /** Upper bound on a unifont glyph's advance, in text pixels. */
    const val UNICODE_FALLBACK_WIDTH_PX = 16

    /** Widest line of [text], in text pixels. */
    fun textWidthPx(text: String): Int =
        text.split('\n').maxOfOrNull { line -> line.sumOf { charWidthPx(it) } } ?: 0

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
