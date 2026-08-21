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

    /**
     * Codepoint for the crop whose source origin is (srcX, srcY), drawn at
     * [yOffset] pixels below its natural baseline — or null if this sprite has
     * no such crop.
     *
     * A bitmap glyph's vertical placement is baked into its provider `ascent`,
     * so a crop drawn at N distinct within-row offsets needs N codepoints, the
     * same way [io.schemat.displaykit.sprite.SpriteGlyphs] treats whole
     * sprites. Implementations allocate on demand and must keep a codepoint
     * stable once handed out — a client that already downloaded the pack would
     * otherwise render tofu.
     *
     * [yOffset] is never positive: `ascent <= height` is client-enforced and
     * `ascent` already sits at its maximum when the offset is zero.
     */
    fun codepointFor(id: SpriteId, srcX: Int, srcY: Int, yOffset: Int): Int?

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
        if (entry.nineSlice == null) {
            // Silently drawing nothing here produced a frame that vanished at
            // every size while `fill` threw for an undersized rect — the same
            // rule with opposite failure modes. Say so once instead.
            SpriteDiagnostics.warnOnce(
                "nine-slice:${entry.id}",
                "Cannot draw ${entry.id} as a nine-slice frame: the sprite index carries " +
                    "no nine-slice metadata for it, so there are no crops to place. " +
                    "Draw it with icon() at its natural ${entry.width}x${entry.height}, " +
                    "or re-run :libs:displaykit:pack:generateSpriteIndex if you expect " +
                    "this sprite to be nine-sliced."
            )
            return
        }
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
            // drawGlyph derives the within-row remainder from y and asks for
            // the matching variant — exactly as SpriteCanvas.draw does for a
            // whole sprite. Resolving a codepoint here without that offset is
            // what used to collapse every region at a y that is not a
            // multiple of TextMetrics.FONT_LINE_HEIGHT_PX onto its row's
            // baseline.
            canvas.drawGlyph(
                x = rect.x + p.dstX,
                y = rect.y + p.dstY,
                advanceWidth = p.srcW + 1,   // bitmap glyph advance is width + 1
                tint = tint
            ) { yOffset ->
                source.codepointFor(entry.id, p.srcX, p.srcY, yOffset)
                    ?.let { String(Character.toChars(it)) }
            }
        }
    }
}
