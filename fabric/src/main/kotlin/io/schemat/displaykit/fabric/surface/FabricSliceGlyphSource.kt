package io.schemat.displaykit.fabric.surface

import io.schemat.displaykit.pack.SpriteSliceProvider
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.surface.SliceGlyphSource

/**
 * Bridges `core`'s SliceGlyphSource to the `pack` module's committed crops.
 *
 * `core` cannot depend on `pack`, so this lives in `fabric`, which depends on
 * both, and is installed at init.
 */
object FabricSliceGlyphSource : SliceGlyphSource {
    override fun request(id: SpriteId) = SpriteSliceProvider.request(id)
    override fun codepointFor(id: SpriteId, srcX: Int, srcY: Int, ascent: Int): Int? =
        SpriteSliceProvider.codepointFor(id, srcX, srcY, ascent)
}
