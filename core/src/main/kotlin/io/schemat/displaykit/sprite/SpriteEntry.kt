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
 */
data class SpriteEntry(
    val id: SpriteId,
    val width: Int,
    val height: Int,
    val texture: String,
    val animated: Boolean = false,
    val greyscale: Boolean = false,
    val nineSlice: NineSlice? = null
) {
    /** Eligible to be emitted as a by-reference bitmap font glyph. */
    val glyphEligible: Boolean get() = !animated

    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
}
