package io.schemat.displaykit.render

import io.schemat.displaykit.sprite.SpriteId

/**
 * What a world surface is drawn with.
 *
 * `OverlayCell.material` used to be a bare [BlockStateRef], which baked the
 * 16 stained-glass dyes into the API — so a product could define a 24-bit
 * theme and still be unable to use it for world highlighting.
 *
 * [Block] stays the default so existing callers keep working unchanged.
 */
sealed interface SurfaceMaterial {

    /** A block display. The original behaviour; limited to real block states. */
    data class Block(val state: BlockStateRef) : SurfaceMaterial

    /** A tinted sprite quad. Tint is arbitrary 24-bit colour. */
    data class Sprite(val id: SpriteId, val tint: DkColor? = null) : SurfaceMaterial

    /** A flat colour, drawn as a text display background. */
    data class Solid(val color: DkColor) : SurfaceMaterial
}

/** Convenience for the common case of keeping the old block-based behaviour. */
fun BlockStateRef.asMaterial(): SurfaceMaterial = SurfaceMaterial.Block(this)
