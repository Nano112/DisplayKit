package io.schemat.displaykit.sprite

/**
 * Nine-slice border insets, harvested from a texture's `.mcmeta`
 * `gui.scaling` block.
 *
 * The client honours this metadata only when drawing GUI screens — never for
 * a sprite in a text component. DisplayKit implements slicing itself; these
 * insets just save us from hardcoding them.
 */
data class NineSlice(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val stretchInner: Boolean = false
)

/**
 * One sprite's true dimensions and rendering eligibility.
 *
 * @param texture Namespaced path of the source PNG relative to `textures/`,
 *   e.g. `minecraft:gui/sprites/hud/hotbar.png`. This is exactly the value a
 *   bitmap font provider's `file` field takes, which is what lets DisplayKit
 *   reference vanilla textures without shipping any image bytes.
 * @param animated True when the source has an `.mcmeta` `animation` block.
 *   Such sprites can never be font glyphs — a glyph renders the whole strip.
 * @param greyscale True when every non-transparent pixel is grey. Font glyph
 *   tinting is multiplicative, so only greyscale sources tint cleanly.
 * @param trimmedWidth The rightmost non-empty pixel column, plus one — the
 *   width the client measures this texture at when it becomes a bitmap glyph.
 *   Transparent right-hand padding is NOT counted, so this is `<= width` and
 *   frequently strictly less (real vanilla items measured 16 -> 14). Defaults
 *   to [width] for hand-built entries that carry no pixel data; the generated
 *   index always records the measured value. See [glyphAdvance].
 * @param averageColor Packed `0xRRGGBB` mean colour of this sprite's pixels
 *   (alpha ignored), used by [io.schemat.displaykit.surface.Surface] as the
 *   tint for the flat fills that stand in for a nine-slice crop under
 *   [io.schemat.displaykit.surface.RenderMode.ENTITIES]. For a sprite WITH
 *   [nineSlice] metadata this is measured from the CENTRE region only (inside
 *   the borders) — that is the part a flat fill actually replaces.
 *
 *   `null` means the measured region has NO opaque pixels, and is a distinct
 *   instruction from any colour: substitute nothing, because the real sprite
 *   draws nothing there. This used to be reported as white, which is also a
 *   perfectly ordinary colour for a sprite to be, so a hollow frame
 *   (`gui/widget/tab_selected`, whose centre is entirely transparent) was
 *   indistinguishable from a white one and got filled with an opaque white
 *   slab. Animated sprites are also `null`: averaging a strip would blend
 *   every frame together. Hand-built entries that carry no pixel data default
 *   to `null` for the same reason — no measurement was made, so no
 *   substitution is warranted.
 */
data class SpriteEntry(
    val id: SpriteId,
    val width: Int,
    val height: Int,
    val texture: String,
    val animated: Boolean = false,
    val greyscale: Boolean = false,
    val nineSlice: NineSlice? = null,
    val trimmedWidth: Int = width,
    val averageColor: Int? = null
) {
    /** Eligible to be emitted as a by-reference bitmap font glyph. */
    val glyphEligible: Boolean get() = !animated

    /**
     * How far the text cursor moves when this sprite is drawn as a glyph.
     *
     * The client does NOT advance by the declared width. `BitmapProvider$
     * Definition.getActualGlyphWidth` scans columns right-to-left and stops at
     * the first with any non-zero luminance-or-alpha, yielding
     * [trimmedWidth]; the advance is then `round(trimmedWidth * scale) + 1`.
     * DisplayKit emits one glyph per texture at its native height, so the
     * scale is 1 and this reduces to `trimmedWidth + 1`.
     *
     * Using [width] here instead is a per-sprite under-advance that
     * accumulates across a row and collapses a grid into an overlapping heap.
     * See `docs/superpowers/specs/2026-08-21-text-display-layout-truth.md`.
     */
    val glyphAdvance: Int get() = trimmedWidth + 1

    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    /**
     * The render height that makes this sprite fit inside [boxW] x [boxH]
     * without distorting it.
     *
     * A bitmap glyph is scaled by `renderHeight / height`, and its width
     * scales by the same factor — there is no independent width control — so
     * fitting a box means choosing the height whose induced width also fits.
     * Never returns less than 1: a zero-height glyph is rejected by the
     * client.
     */
    fun fitHeight(boxW: Int, boxH: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val byWidth = (boxW.toLong() * height / width).toInt()
        return maxOf(1, minOf(boxH, byWidth))
    }

    /** Rendered width once scaled to [renderHeight]. */
    fun scaledWidth(renderHeight: Int): Int =
        if (height <= 0) 0 else Math.round(width.toFloat() * renderHeight / height)

    /**
     * Cursor advance once scaled to [renderHeight] — the client's
     * `round(trimmedWidth * scale) + 1`, with `scale = renderHeight / height`.
     */
    fun scaledAdvance(renderHeight: Int): Int =
        if (height <= 0) 1
        else Math.round(trimmedWidth.toFloat() * renderHeight / height) + 1
}
