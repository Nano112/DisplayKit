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
     * @param y The item's canvas Y, unquantised. Kept for diagnostics
     *   ([itemPositions]) — [buildRows] groups by [row], not by re-deriving
     *   it from [y], because a short sprite/slice glyph's row can fall below
     *   `y / TextMetrics.FONT_LINE_HEIGHT_PX` (see [GlyphPlacement]). Such
     *   items have already baked their within-row remainder into [content]
     *   via [SpriteGlyphs] ascent, at draw() time.
     * @param row The text-component row (0-based, top to bottom) this item
     *   is emitted on. Equal to `y / TextMetrics.FONT_LINE_HEIGHT_PX` for
     *   plain text, but may be an earlier row for a sprite/slice glyph too
     *   short to satisfy its natural row's required ascent.
     * @param advanceWidth How far the text cursor moves once this item is
     *   emitted — i.e. the pixel position immediately after it. For a sprite
     *   glyph this is [SpriteEntry.glyphAdvance], which is the sprite's
     *   TRIMMED width plus one, not its declared width plus one: the client
     *   measures a bitmap glyph by scanning for its rightmost non-empty
     *   column. Using the declared width here under-advances by a per-sprite
     *   amount that accumulates across a row.
     */
    private data class Item(
        val content: String,
        val font: String?,
        val x: Int,
        val y: Int,
        val row: Int,
        val advanceWidth: Int,
        val tint: DkColor?,
        /**
         * Depth layer. Everything in one text display is coplanar, so
         * overlapping glyphs z-fight; [Surface] emits one entity per distinct
         * layer, each stepped toward the viewer. See [Surface.toEntities].
         */
        val layer: Int = 0
    )

    private val items = mutableListOf<Item>()

    /**
     * Draw [entry] with its top-left at ([x], [y]) in canvas pixels, y growing
     * downward.
     *
     * [y] and [SpriteEntry.height] are resolved via [GlyphPlacement] to a
     * (row, ascent) pair that puts the glyph's TOP at exactly [y] — falling
     * back to an earlier text-component row when the sprite is too short for
     * its natural row's required ascent (see [GlyphPlacement] for when that
     * applies). When even row 0 cannot satisfy `ascent <= height`, this warns
     * once via [SpriteDiagnostics] and draws nothing rather than handing a
     * font provider an ascent the client would refuse to load.
     *
     * @throws IllegalArgumentException if [entry] is animated.
     * @throws IllegalArgumentException if [y] is negative — the canvas origin
     *   is its top-left and canvas y never goes negative.
     */
    @JvmOverloads
    fun draw(
        entry: SpriteEntry,
        x: Int,
        y: Int,
        tint: DkColor? = null,
        renderHeight: Int = entry.height
    ) {
        require(!entry.animated) {
            "Sprite ${entry.id} is animated and cannot be composited — " +
                "a glyph renders the whole strip. Use SpriteDisplay instead."
        }
        require(y >= 0) {
            "SpriteCanvas.draw requires y >= 0 (canvas y grows downward from " +
                "the top of the canvas), but got y=$y for ${entry.id}."
        }
        require(renderHeight > 0) {
            "SpriteCanvas.draw requires renderHeight > 0, got $renderHeight " +
                "for ${entry.id}."
        }
        if (tint != null) SpriteDiagnostics.checkTintable(entry)
        // Placement and advance both follow the RENDERED size, not the
        // sprite's native size -- a scaled glyph occupies a scaled box.
        val placement = GlyphPlacement.resolve(y, renderHeight) ?: run {
            SpriteDiagnostics.warnOnce(
                "ascent-unsatisfiable:${entry.id}:y=$y:h=$renderHeight",
                "Cannot draw ${entry.id} (render height $renderHeight) with its top at " +
                    "canvas y=$y: even row 0 cannot produce a legal ascent " +
                    "(ascent <= height). Skipping this draw."
            )
            return
        }
        items += Item(
            content = SpriteGlyphs.charsFor(entry, placement.ascent, renderHeight),
            font = SpriteGlyphs.FONT_ID,
            x = x,
            y = y,
            row = placement.row,
            advanceWidth = entry.scaledAdvance(renderHeight),
            tint = tint,
            layer = currentLayer
        )
    }

    /**
     * Draw literal text at ([x], [y]).
     *
     * Text has no per-glyph `ascent` mechanism, so within a row it cannot
     * shift vertically — [y] is quantised to
     * `TextMetrics.FONT_LINE_HEIGHT_PX` (its row, `y / FONT_LINE_HEIGHT_PX`) and the
     * text always sits at that row's baseline. Sprites drawn via [draw] are
     * pixel-exact because their glyph ascent absorbs the remainder; text
     * cannot do the same, so pick `y` values that are multiples of
     * `TextMetrics.FONT_LINE_HEIGHT_PX` when exact placement matters.
     */
    fun text(s: String, x: Int, y: Int, tint: DkColor? = null) {
        items += Item(
            content = s,
            font = null,
            x = x,
            y = y,
            // Nearest row, not the one below. Truncating sent a label up to
            // a full row above where it was asked for; rounding halves the
            // worst case and centres the residual. Exact placement needs a
            // row-aligned y -- see TextMetrics.rowAlignedY.
            row = TextMetrics.rowAlignedY(y) / TextMetrics.FONT_LINE_HEIGHT_PX,
            advanceWidth = TextMetrics.textWidthPx(s),
            layer = currentLayer,
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
     * Mirrors [draw] exactly, and must keep doing so: [y] and [height] are
     * resolved via [GlyphPlacement] to a (row, ascent) pair that has to be
     * baked into the glyph's `ascent`, or the glyph collapses onto its row's
     * baseline. Nine-slice crops can be as short as 1px, which is exactly the
     * case [GlyphPlacement] falls back to an earlier row for.
     *
     * Slice codepoints are not owned by [SpriteGlyphs] — they reference
     * generated crop textures and live in
     * [io.schemat.displaykit.surface.SliceGlyphSource] — so the caller cannot
     * be handed a finished string up front: it does not know the ascent yet.
     * Instead it passes [resolve], which this method calls with the required
     * `ascent`. Computing it here rather than at each call site is what stops
     * the slice path from drifting away from [draw] again.
     *
     * When [GlyphPlacement] cannot satisfy `ascent <= height` even at row 0,
     * this warns once via [SpriteDiagnostics] and draws nothing.
     *
     * @param resolve Returns the characters for the requested `ascent`
     *   variant, or null when the variant cannot be resolved (in which case
     *   nothing is drawn).
     */
    fun drawGlyph(
        x: Int,
        y: Int,
        height: Int,
        advanceWidth: Int,
        tint: DkColor? = null,
        resolve: (ascent: Int) -> String?
    ) {
        require(y >= 0) { "Canvas y must be >= 0 (got $y); the canvas origin is its top-left." }
        val placement = GlyphPlacement.resolve(y, height) ?: run {
            SpriteDiagnostics.warnOnce(
                "ascent-unsatisfiable:slice:h=$height:y=$y",
                "Cannot place a ${height}px-tall slice glyph with its top at canvas " +
                    "y=$y: even row 0 cannot produce a legal ascent (ascent <= height). " +
                    "Skipping this draw."
            )
            return
        }
        val chars = resolve(placement.ascent) ?: return
        items += Item(content = chars, font = SpriteGlyphs.SLICE_FONT_ID, x = x, y = y,
                      row = placement.row, advanceWidth = advanceWidth, tint = tint,
                      layer = currentLayer)
    }

    fun clear() = items.clear()

    /**
     * Row model: each item belongs to row `item.y / TextMetrics.FONT_LINE_HEIGHT_PX`.
     * [buildRows] emits rows in ascending order — including rows with no
     * items of their own, since skipping them would collapse the vertical
     * gap they represent — and [buildChildren] joins them with a single
     * `"\n"` child between each pair.
     *
     * Within a row, items are sorted by `x` alone (stable — ties keep
     * insertion order) and strung along a cursor that resets to `0` at the
     * start of every row. The gap between the cursor and each item's `x` is
     * closed with a [Spacing] advance, which is allowed to go negative: a
     * glyph's advance exceeds its drawn width by 1, so touching or
     * overlapping sprites correct the cursor backward.
     */
    /**
     * Pad every emitted row out to [widthPx], and emit at least
     * [anchoredRowCount] rows, so the client measures this canvas's text block
     * at exactly its declared bounds.
     *
     * A text display is positioned by the CENTRE of the block the client
     * measures, not by the canvas origin (see
     * `docs/superpowers/specs/2026-08-21-text-display-layout-truth.md`). That
     * measurement depends on the content, so without anchoring a surface would
     * shift underneath itself whenever a widget changed width — and any
     * placement maths would have to predict the client's own measurement to
     * stay aligned. Anchoring makes the block size a constant the caller
     * already knows.
     *
     * Off by default: the padding uses the spacing font, which would make
     * [requiresPack] true for an otherwise plain-text canvas and cost it the
     * legible pack-disabled fallback. [requiresPack] therefore always inspects
     * the UNANCHORED children.
     */
    var anchorToBounds: Boolean = false

    /**
     * Layer assigned to subsequent draws. [Surface] raises this as it paints
     * chrome, then slots, then icons, then text, so each sits a depth step in
     * front of the last.
     */
    var currentLayer: Int = 0

    /**
     * Rows this canvas actually emits — the block height the client measures,
     * divided by the line pitch. Content taller than [heightPx] still emits
     * its own rows, so this is a max, not [anchoredRowCount].
     */
    fun emittedRowCount(): Int = lastRowIndex() + 1

    /** Distinct layers with content, back to front. */
    internal fun layerRowWidths(layer: Int): List<Int> =
        buildRows(anchorToBounds, layer).map { it.endCursorX }

    internal fun layerItemCount(layer: Int): Int = items.count { it.layer == layer }

    fun layers(): List<Int> = items.map { it.layer }.distinct().sorted()

    /**
     * The row index every layer emits up to.
     *
     * Canvas-level on purpose: a layer holding only the title would otherwise
     * emit one row while the chrome layer emits 27, giving the two different
     * block heights and therefore different entity origins — and the layers
     * would drift apart instead of stacking.
     */
    private fun lastRowIndex(): Int {
        val contentLast = items.maxOfOrNull { it.row } ?: 0
        return if (anchorToBounds) maxOf(contentLast, anchoredRowCount() - 1) else contentLast
    }

    /**
     * The width every layer pads to — the widest row across ALL layers, never
     * less than [widthPx].
     *
     * Also canvas-level. The nine-slice frame overshoots [widthPx] by a pixel,
     * so anchoring each layer to its own widest row would make the frame's
     * block one pixel wider than the icons' and offset them by half of that.
     */
    fun blockWidthPx(): Int = maxOf(widthPx, rawMaxRowAdvance())

    private fun rawMaxRowAdvance(): Int =
        items.groupBy { it.row }.values.maxOfOrNull { row ->
            var cursor = 0
            for (item in row.sortedBy { it.x }) cursor = item.x + item.advanceWidth
            cursor
        } ?: 0

    /**
     * Rows needed to cover [heightPx] at the renderer's line pitch — the block
     * height an anchored canvas guarantees.
     */
    fun anchoredRowCount(): Int =
        (heightPx + TextMetrics.FONT_LINE_HEIGHT_PX - 1) / TextMetrics.FONT_LINE_HEIGHT_PX

    /** One row's emitted children, plus where its cursor ended up. */
    private data class Row(val children: List<TextComponent>, val endCursorX: Int)

    /**
     * The row/cursor walk shared by [toTextComponent], [requiresPack], and
     * [maxRowAdvance], so none of the three can ever drift apart — whatever
     * this produces is exactly what would render, exactly what is inspected
     * for pack dependence, and exactly what the widest row actually measures.
     */
    private fun buildRows(
        anchor: Boolean = anchorToBounds,
        layer: Int? = null
    ): List<Row> {
        if (items.isEmpty()) return emptyList()

        val visible = if (layer == null) items else items.filter { it.layer == layer }
        val byRow = visible.groupBy { it.row }
        val maxRow = byRow.keys.maxOrNull() ?: 0
        // Anchoring pads out to the canvas bounds so the client measures the
        // block at exactly (width x anchoredRowCount), which is what lets
        // Surface place the entity from a known offset instead of predicting
        // the measurement. See the property's KDoc.
        // Shared across layers -- see lastRowIndex()/blockWidthPx().
        val lastRow = if (anchor) lastRowIndex() else maxRow
        val padTo = if (anchor) blockWidthPx() else 0

        val rows = mutableListOf<Row>()
        for (row in 0..lastRow) {
            val children = mutableListOf<TextComponent>()
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
            if (anchor && cursorX < padTo) {
                children += TextComponent(
                    text = Spacing.advance(padTo - cursorX),
                    font = Spacing.FONT_ID
                )
                cursorX = padTo
            }
            rows += Row(children, cursorX)
        }

        return rows
    }

    private fun buildChildren(
        anchor: Boolean = anchorToBounds,
        layer: Int? = null
    ): List<TextComponent> {
        val rows = buildRows(anchor, layer)
        val children = mutableListOf<TextComponent>()
        rows.forEachIndexed { index, row ->
            if (index > 0) children += TextComponent(text = "\n")
            children += row.children
        }
        return children
    }

    fun toTextComponent(
        anchor: Boolean = anchorToBounds,
        layer: Int? = null
    ): TextComponent {
        val children = buildChildren(anchor, layer)
        if (children.isEmpty()) return TextComponent.EMPTY
        return TextComponent(children = children)
    }

    /**
     * The largest end-cursor position across all rows — i.e. the pixel width
     * of the widest row this canvas would actually emit.
     *
     * Callers (see `Surface.toEntity()`) use this to set the text display's
     * `lineWidth` so Minecraft's client-side line wrapping can never kick in:
     * `DisplayRenderer$TextDisplayRenderer.splitLines` wraps any row wider
     * than the entity's `lineWidth`, and [VirtualTextDisplay]'s default of 200
     * is narrower than plenty of real canvases. Derived from the same
     * [buildRows] walk [toTextComponent] uses, so this can never drift from
     * what is actually emitted.
     */
    fun maxRowAdvance(): Int = buildRows().maxOfOrNull { it.endCursorX } ?: 0

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
    fun requiresPack(): Boolean = buildChildren(anchor = false).any { it.font != null }
}
