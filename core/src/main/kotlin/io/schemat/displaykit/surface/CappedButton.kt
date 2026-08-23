package io.schemat.displaykit.surface

import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex

/**
 * A button assembled from a left cap, tiled middles and a right cap.
 *
 * ### Why not just nine-slice `widget/button`
 *
 * `gui/widget/button` is 200x20 with a 3px border, so its centre tile is
 * 194px wide. Slice crops are fixed images referenced by a font, so the
 * centre cannot be cropped narrower -- the sprite simply cannot be drawn
 * below 200px, which is far wider than most controls want. `widget/tab` is
 * the only narrow alternative at 130px, and its SELECTED variants are
 * completely hollow (their centre is transparent because vanilla merges a
 * selected tab into the panel below it), so a free-floating one renders as
 * an empty outline.
 *
 * The advancement tabs solve both. Each is 28x32, solid, and comes in left /
 * middle / right with a `_selected` for every position -- so caps plus a
 * stretched middle give ANY width at or above two caps, in a state that
 * actually looks selected.
 *
 * ### Layers
 *
 * Each layer sits one elevation above the last, which is what keeps them off
 * each other's depth plane. Two sprites drawn at the same elevation AND the
 * same kind share a depth key, land coplanar, and z-fight -- which is exactly
 * what a ground painted under a frame at the same elevation did.
 *
 * ```
 *   base    optional block display, real volume behind the face
 *   face    left cap | middle x N | right cap
 *   label   text, row-aligned so it is drawn where the centring says
 * ```
 */
object CappedButton {

    /** Native size of every advancement-tab piece. */
    const val CAP_W = 28
    const val CAP_H = 32

    /** Smallest width that fits both caps, at native scale. */
    const val MIN_W = CAP_W * 2

    private fun piece(position: String, selected: Boolean): SpriteId {
        val suffix = if (selected) "_selected" else ""
        return SpriteId("gui", "advancements/tab_above_$position$suffix")
    }

    /**
     * Visual state. Hover is a TINT rather than a sprite, because the
     * advancement tabs ship no highlighted variant -- and inventing one by
     * swapping to the selected sprite would make a hovered button
     * indistinguishable from the selected one, which is the mistake the tab
     * strip already made.
     */
    enum class State { NORMAL, HOVERED, SELECTED, SELECTED_HOVERED }

    /** Multiplicative lift for a hovered face. Greyscale-ish sources lift cleanly. */
    private val HOVER_TINT = DkColor(255, 255, 255, 255)
    private val HOVER_LIFT = DkColor(255, 210, 225, 255)

    private fun tintFor(state: State): DkColor? = when (state) {
        State.HOVERED, State.SELECTED_HOVERED -> HOVER_LIFT
        else -> null
    }

    private fun isSelected(state: State) =
        state == State.SELECTED || state == State.SELECTED_HOVERED

    private fun entry(id: SpriteId): SpriteEntry? = SpriteIndex.bundled.get(id)

    /**
     * Every sprite this button can draw, for pre-warming.
     *
     * A widget's hover and selected appearances are part of its cost. Warming
     * only the state currently on screen means the pack grows the first time
     * the pointer touches it, and every client re-downloads mid-interaction.
     */
    fun statesFor(): List<SpriteEntry> =
        listOf(false, true).flatMap { sel ->
            listOf("left", "middle", "right").mapNotNull { entry(piece(it, sel)) }
        }

    /**
     * Pixel width of one piece drawn at [height].
     *
     * A composited sprite is a bitmap glyph, and a glyph scales UNIFORMLY --
     * its width follows its height. There is no non-uniform stretch, so the
     * middle cannot simply be pulled to fit: it is TILED instead, which is
     * why a button's width comes in whole pieces.
     */
    fun pieceWidth(height: Int): Int =
        entry(piece("middle", false))?.scaledWidth(height) ?: 0

    /**
     * Widths this button can actually be, at [height]: two caps plus whole
     * middle tiles. Callers should size to one of these rather than assume
     * arbitrary widths work.
     */
    fun widthFor(minWidth: Int, height: Int = heightFor()): Int {
        val piece = pieceWidth(height)
        if (piece <= 0) return minWidth
        val middles = (((minWidth - 2 * piece) + piece - 1) / piece).coerceAtLeast(1)
        return piece * (2 + middles)
    }

    /**
     * Draw the button's face into [rect]: left cap, tiled middles, right cap.
     *
     * Returns false if the sprites are missing or the rect is too small, so a
     * caller can skip its label and hit region rather than leave an
     * invisible-but-clickable rectangle.
     */
    fun paintFace(p: SurfacePainter, rect: Rect, state: State): Boolean {
        val selected = isSelected(state)
        val left = entry(piece("left", selected)) ?: return false
        val mid = entry(piece("middle", selected)) ?: return false
        val right = entry(piece("right", selected)) ?: return false

        val h = rect.h
        val w = left.scaledWidth(h)
        if (w <= 0 || rect.w < w * 2) return false
        val tint = tintFor(state)

        // Each piece is drawn into a box exactly its own fitted size, so
        // iconFitted's centring offset is zero and the pieces butt together
        // with no seam.
        fun put(e: SpriteEntry, x: Int) = p.iconFitted(e, x, rect.y, w, h, tint)

        put(left, rect.x)
        var x = rect.x + w
        val lastCapX = rect.right - w
        while (x + w <= lastCapX) {
            put(mid, x)
            x += w
        }
        // Any shortfall from a width that is not a whole number of pieces is
        // covered by overlapping the right cap leftward rather than leaving a
        // gap; widthFor() lets a caller avoid the situation entirely.
        put(right, lastCapX)
        return true
    }

    /**
     * The height a button should be so its label centres exactly.
     *
     * Text only sits on a text row, so an even number of rows can never
     * centre a line. See [TextMetrics.centringHeight].
     */
    fun heightFor(minHeight: Int = CAP_H): Int = TextMetrics.centringHeight(minHeight)
}

/**
 * A complete button: optional block base, capped sprite face, centred label,
 * and a hit region.
 *
 * Each layer is one elevation above the last. Drawing two sprites at the same
 * elevation and kind gives them the same depth key, so they land coplanar and
 * z-fight -- which is what a ground painted under a frame at one elevation
 * did, and what this arrangement exists to prevent.
 *
 * [base] gives the button real volume behind its face. It is a block display,
 * so it is genuinely solid rather than a painted rectangle -- worth it for a
 * control that should read as physical, and skippable for flat chrome.
 */
fun SurfacePainter.cappedButton(
    id: String,
    rect: Rect,
    text: String,
    state: CappedButton.State = CappedButton.State.NORMAL,
    base: BlockStateRef? = null,
    baseThickness: Float = 0.0625f,
    onClick: () -> Unit = {}
) = elevate {
    if (base != null) blockPanel(base, rect, baseThickness)

    // One elevation up from the base, so the face never shares its plane.
    // elevate returns Unit, so the outcome comes back through a var.
    var painted = false
    elevate { painted = CappedButton.paintFace(this, rect, state) }
    if (!painted) return@elevate

    elevate(2) {
        val width = TextMetrics.textWidthPx(text)
        label(
            text,
            rect.x + (rect.w - width) / 2,
            TextMetrics.rowAlignedY(rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2)
        )
    }
    region(id, rect, onClick)
}
