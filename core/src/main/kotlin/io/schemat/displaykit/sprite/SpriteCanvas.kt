package io.schemat.displaykit.sprite

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextComponent
import io.schemat.displaykit.render.TextMetrics

/**
 * Horizontal advances via the `space` font provider in `spacing.json`.
 *
 * That file ships powers of two from 1 to 128 in both directions, so any
 * integer advance up to +-255 is a sum of at most eight characters.
 */
object Spacing {

    const val FONT_ID = "displaykit:spacing"

    // Codepoints must match the advances declared in
    // SpriteAssetProvider.registerSpacingIcons(), which writes
    // assets/displaykit/font/spacing.json.
    private val NEGATIVE = mapOf(
        1 to '\uF001', 2 to '\uF002', 4 to '\uF004', 8 to '\uF008',
        16 to '\uF010', 32 to '\uF020', 64 to '\uF040', 128 to '\uF080'
    )
    private val POSITIVE = mapOf(
        1 to '\uF101', 2 to '\uF102', 4 to '\uF104', 8 to '\uF108',
        16 to '\uF110', 32 to '\uF120', 64 to '\uF140', 128 to '\uF180'
    )

    /** Characters producing a horizontal advance of exactly [px] pixels. */
    fun advance(px: Int): String {
        if (px == 0) return ""
        val table = if (px < 0) NEGATIVE else POSITIVE
        var remaining = kotlin.math.abs(px)
        val sb = StringBuilder()
        for (size in listOf(128, 64, 32, 16, 8, 4, 2, 1)) {
            while (remaining >= size) {
                sb.append(table.getValue(size))
                remaining -= size
            }
        }
        return sb.toString()
    }
}

/**
 * Compositor render mode: many sprites at arbitrary integer pixel positions
 * inside a **single** text display.
 *
 * X placement uses [Spacing] advances, summed from a single running cursor;
 * Y placement uses per-glyph `ascent`, which is why each distinct Y offset
 * costs its own font provider entry.
 *
 * This is the mode that makes 2D sprite content affordable — `GridMapTab`
 * draws a 25x25 minimap with 625 block-display entities today; the same image
 * here is one entity.
 *
 * Integer-pixel and axis-aligned by construction. Sub-pixel or rotated content
 * must use [SpriteDisplay] and pay one entity per quad.
 */
class SpriteCanvas(val widthPx: Int, val heightPx: Int) {

    /**
     * One placed item, ready to be flattened.
     *
     * @param advanceWidth How far the text cursor moves once this item is
     *   emitted — i.e. the pixel position immediately after it. For a sprite
     *   glyph this is `entry.width + 1`, matching the client's bitmap glyph
     *   advance formula `round(width * height / textureHeight) + 1` once
     *   `height` is set to the sprite's true height (scale factor 1).
     */
    private data class Item(
        val content: String,
        val font: String?,
        val x: Int,
        val advanceWidth: Int,
        val tint: DkColor?
    )

    private val items = mutableListOf<Item>()

    /**
     * Draw [entry] with its top-left at ([x], [y]) in canvas pixels, y growing
     * downward.
     *
     * @throws IllegalArgumentException if [entry] is animated.
     * @throws IllegalArgumentException if [y] is negative. `draw` shifts the
     *   glyph's baked `ascent` down by [y] pixels (see [SpriteGlyphs]), and
     *   upward shift is not representable — `ascent <= height` is
     *   client-enforced and already sits at its maximum when `y = 0`.
     */
    fun draw(entry: SpriteEntry, x: Int, y: Int, tint: DkColor? = null) {
        require(!entry.animated) {
            "Sprite ${entry.id} is animated and cannot be composited — " +
                "a glyph renders the whole strip. Use SpriteDisplay instead."
        }
        require(y >= 0) {
            "SpriteCanvas.draw requires y >= 0 (canvas y grows downward from " +
                "the top of the canvas), but got y=$y for ${entry.id}."
        }
        if (tint != null) SpriteDiagnostics.checkTintable(entry)
        items += Item(
            content = SpriteGlyphs.charsFor(entry, -y),
            font = SpriteGlyphs.FONT_ID,
            x = x,
            advanceWidth = entry.width + 1,
            tint = tint
        )
    }

    /**
     * Draw literal text at ([x], [y]).
     *
     * Text has no per-glyph `ascent` mechanism, so [y] does not move it
     * vertically — everything the canvas draws lands on one line in one text
     * display. The parameter is kept for a uniform `draw`/`text` call shape.
     */
    fun text(s: String, x: Int, y: Int, tint: DkColor? = null) {
        items += Item(
            content = s,
            font = null,
            x = x,
            advanceWidth = TextMetrics.textWidthPx(s),
            tint = tint
        )
    }

    fun clear() = items.clear()

    /**
     * Flatten to a single component.
     *
     * Items are sorted by `x` alone (stable — ties keep insertion order) and
     * strung along one running cursor. The gap between the cursor and each
     * item's `x` is closed with a [Spacing] advance, which is allowed to go
     * negative: a glyph's advance exceeds its drawn width by 1, so touching
     * or overlapping sprites correct the cursor backward.
     */
    fun toTextComponent(): TextComponent {
        if (items.isEmpty()) return TextComponent.EMPTY

        val children = mutableListOf<TextComponent>()
        var cursorX = 0

        for (item in items.sortedBy { it.x }) {
            val gap = item.x - cursorX
            if (gap != 0) {
                children += TextComponent(text = Spacing.advance(gap), font = Spacing.FONT_ID)
            }
            children += TextComponent(
                text = item.content,
                font = item.font,
                color = item.tint
            )
            cursorX = item.x + item.advanceWidth
        }

        return TextComponent(children = children)
    }
}
