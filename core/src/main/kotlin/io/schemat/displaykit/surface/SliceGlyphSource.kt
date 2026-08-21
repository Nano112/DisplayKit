package io.schemat.displaykit.surface

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.sprite.SpriteCanvas
import io.schemat.displaykit.sprite.SpriteDiagnostics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId

/**
 * Supplies the glyph codepoints for cropped nine-slice regions.
 *
 * The crops live in the `pack` module, but painting happens in `core`, which
 * must not depend on `pack`. So `core` declares this interface and the platform
 * layer installs the real implementation at init — the same inversion the
 * platform provider already uses.
 */
interface SliceGlyphSource {
    /** Queue this sprite's crops for the next pack build. */
    fun request(id: SpriteId)

    /** Codepoint for the crop whose source origin is (srcX, srcY), or null. */
    fun codepointFor(id: SpriteId, srcX: Int, srcY: Int): Int?

    companion object {
        /** Installed by the platform layer. Null means nine-slice is unavailable. */
        @JvmStatic
        var installed: SliceGlyphSource? = null
    }
}

/**
 * Paints a nine-sliced sprite onto a canvas at an arbitrary size.
 *
 * Placement comes from [NineSliceLayout]; this only turns placements into glyph
 * draws. With no [SliceGlyphSource] installed it warns once and draws nothing,
 * rather than crashing a render pass.
 */
object NineSlicePainter {

    fun paint(canvas: SpriteCanvas, entry: SpriteEntry, rect: Rect, tint: DkColor? = null) {
        val regions = NineSliceLayout.regionsFor(entry, rect.w, rect.h)
        if (regions.isEmpty()) return

        val source = SliceGlyphSource.installed ?: run {
            SpriteDiagnostics.warnOnce(
                "slice-source",
                "No SliceGlyphSource installed, so nine-slice frames cannot render. " +
                    "The platform layer installs one at init; this usually means the " +
                    "resource pack is disabled."
            )
            return
        }

        source.request(entry.id)
        for (p in regions) {
            val cp = source.codepointFor(entry.id, p.srcX, p.srcY) ?: continue
            canvas.drawGlyph(
                chars = String(Character.toChars(cp)),
                x = rect.x + p.dstX,
                y = rect.y + p.dstY,
                advanceWidth = p.srcW + 1,   // bitmap glyph advance is width + 1
                tint = tint
            )
        }
    }
}
