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
 * draws a 25x25 minimap with 625 block-display entities without it; the same
 * image here is one entity.
 *
 * **Requires the DisplayKit resource pack.** `toTextComponent()` emits
 * characters from two fonts that only exist once the pack has reached the
 * client: `displaykit:sprites` (glyphs, written by `SpriteFontProvider`) and
 * `displaykit:spacing` (X advances, written by `SpacingFontProvider`). Both
 * must be registered with the pack builder, and the pack itself must be
 * enabled (`FabricDisplayKit.enableResourcePack`/`glyphsAvailable`). There is
 * no fallback rendering for a canvas without its fonts — without the pack,
 * every character this class emits shows as a missing-glyph box. Callers that
 * need to work with the pack off must branch on `glyphsAvailable` and render
 * something else entirely (see `GridMapTab`), not rely on this class to
 * degrade.
 *
 * Integer-pixel and axis-aligned by construction. Sub-pixel or rotated content
 * must use [SpriteDisplay] and pay one entity per quad.
 */
class SpriteCanvas(val widthPx: Int, val heightPx: Int) {

    /**
     * One placed item, ready to be flattened.
     *
     * @param y The item's canvas Y, unquantised. [toTextComponent] derives its
     *   row from this (`y / TextMetrics.LINE_HEIGHT_PX`) — sprite items have
     *   already baked their within-row remainder into [content] via
     *   [SpriteGlyphs] ascent, at draw() time.
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
        val y: Int,
        val advanceWidth: Int,
        val tint: DkColor?
    )

    private val items = mutableListOf<Item>()

    /**
     * Draw [entry] with its top-left at ([x], [y]) in canvas pixels, y growing
     * downward.
     *
     * [y] is split into a row (`y / TextMetrics.LINE_HEIGHT_PX`, handled by
     * [toTextComponent]) and a within-row remainder (`y % LINE_HEIGHT_PX`),
     * which is baked into the glyph's ascent immediately so the sprite lands
     * pixel-exact regardless of which row it falls in.
     *
     * @throws IllegalArgumentException if [entry] is animated.
     * @throws IllegalArgumentException if [y] is negative. `draw` shifts the
     *   glyph's baked `ascent` down by [y]'s remainder (see [SpriteGlyphs]),
     *   and upward shift is not representable — `ascent <= height` is
     *   client-enforced and already sits at its maximum when the remainder
     *   is `0`.
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
        val remainder = y % TextMetrics.LINE_HEIGHT_PX
        items += Item(
            content = SpriteGlyphs.charsFor(entry, -remainder),
            font = SpriteGlyphs.FONT_ID,
            x = x,
            y = y,
            advanceWidth = entry.width + 1,
            tint = tint
        )
    }

    /**
     * Draw literal text at ([x], [y]).
     *
     * Text has no per-glyph `ascent` mechanism, so within a row it cannot
     * shift vertically — [y] is quantised to
     * `TextMetrics.LINE_HEIGHT_PX` (its row, `y / LINE_HEIGHT_PX`) and the
     * text always sits at that row's baseline. Sprites drawn via [draw] are
     * pixel-exact because their glyph ascent absorbs the remainder; text
     * cannot do the same, so pick `y` values that are multiples of
     * `TextMetrics.LINE_HEIGHT_PX` when exact placement matters.
     */
    fun text(s: String, x: Int, y: Int, tint: DkColor? = null) {
        items += Item(
            content = s,
            font = null,
            x = x,
            y = y,
            advanceWidth = TextMetrics.textWidthPx(s),
            tint = tint
        )
    }

    /** Number of drawn items. For tests and diagnostics. */
    fun itemCount(): Int = items.size

    /** Positions of drawn items, in insertion order. For tests and diagnostics. */
    fun itemPositions(): List<Pair<Int, Int>> = items.map { it.x to it.y }

    /**
     * Draw a slice glyph with its top-left at ([x], [y]) in canvas pixels.
     *
     * Mirrors [draw] exactly, and must keep doing so: [y] is split into a row
     * (`y / TextMetrics.LINE_HEIGHT_PX`, handled by [toTextComponent]) and a
     * within-row remainder (`y % LINE_HEIGHT_PX`) that has to be baked into the
     * glyph's `ascent`, or the glyph collapses onto its row's baseline.
     *
     * Slice codepoints are not owned by [SpriteGlyphs] — they reference
     * generated crop textures and live in
     * [io.schemat.displaykit.surface.SliceGlyphSource] — so the caller cannot
     * be handed a finished string up front: it does not know the offset yet.
     * Instead it passes [resolve], which this method calls with the required
     * `yOffset` (`-remainder`, never positive). Computing the offset here
     * rather than at each call site is what stops the slice path from drifting
     * away from [draw] again.
     *
     * @param resolve Returns the characters for the requested `yOffset`
     *   variant, or null when the variant cannot be resolved (in which case
     *   nothing is drawn).
     */
    fun drawGlyph(
        x: Int,
        y: Int,
        advanceWidth: Int,
        tint: DkColor? = null,
        resolve: (yOffset: Int) -> String?
    ) {
        require(y >= 0) { "Canvas y must be >= 0 (got $y); the canvas origin is its top-left." }
        val remainder = y % TextMetrics.LINE_HEIGHT_PX
        val chars = resolve(-remainder) ?: return
        items += Item(content = chars, font = SpriteGlyphs.SLICE_FONT_ID, x = x, y = y,
                      advanceWidth = advanceWidth, tint = tint)
    }

    fun clear() = items.clear()

    /**
     * Flatten to a single component using a row model.
     *
     * Each item belongs to row `item.y / TextMetrics.LINE_HEIGHT_PX`. Rows
     * are emitted in ascending order, separated by a single `"\n"` child —
     * including rows with no items of their own, since skipping them would
     * collapse the vertical gap they represent.
     *
     * Within a row, items are sorted by `x` alone (stable — ties keep
     * insertion order) and strung along a cursor that resets to `0` at the
     * start of every row. The gap between the cursor and each item's `x` is
     * closed with a [Spacing] advance, which is allowed to go negative: a
     * glyph's advance exceeds its drawn width by 1, so touching or
     * overlapping sprites correct the cursor backward.
     */
    /**
     * The row/cursor walk shared by [toTextComponent] and [requiresPack], so
     * the two can never drift apart — whatever children this produces is
     * exactly what would render, and exactly what is inspected for pack
     * dependence.
     */
    private fun buildChildren(): List<TextComponent> {
        if (items.isEmpty()) return emptyList()

        val byRow = items.groupBy { it.y / TextMetrics.LINE_HEIGHT_PX }
        val maxRow = byRow.keys.max()

        val children = mutableListOf<TextComponent>()
        for (row in 0..maxRow) {
            if (row > 0) {
                children += TextComponent(text = "\n")
            }
            var cursorX = 0
            for (item in byRow[row].orEmpty().sortedBy { it.x }) {
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
        }

        return children
    }

    fun toTextComponent(): TextComponent {
        val children = buildChildren()
        if (children.isEmpty()) return TextComponent.EMPTY
        return TextComponent(children = children)
    }

    /**
     * True when rendering this canvas requires the DisplayKit resource pack.
     *
     * Plain text needs no pack at all. Only two things do: an item drawn with
     * a non-null font (a sprite or slice glyph, which lives in
     * `displaykit:sprites`), and a spacing advance for a non-zero gap (which
     * lives in `displaykit:spacing`). Both surface as a non-null `font` on a
     * child from [buildChildren], so checking that is exact rather than a
     * proxy like "the canvas is non-empty" — an empty text-only canvas with a
     * leading gap still needs the pack for its spacing character.
     */
    fun requiresPack(): Boolean = buildChildren().any { it.font != null }
}
