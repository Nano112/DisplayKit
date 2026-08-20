package io.schemat.displaykit.pack

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import java.awt.image.BufferedImage

/** One cropped sub-rect of a sprite, with its own glyph codepoint. */
data class SliceRegion(
    val entry: SpriteEntry,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val codepoint: Int
)

/**
 * Splits a sprite into the sub-rects a nine-slice needs.
 *
 * A bitmap font provider can only divide a texture into a *uniform*
 * rows x cols grid, but nine-slice borders are not uniform — a border of 3 on
 * a 200-wide sprite is 3/194/3. Cropping is therefore the one sprite operation
 * that cannot be done by reference and must generate textures.
 */
object SpriteSlicer {

    private val cache = mutableMapOf<SpriteId, List<SliceRegion>>()

    fun clear() = cache.clear()

    /**
     * The nine regions, in reading order: top-left, top, top-right, left,
     * center, right, bottom-left, bottom, bottom-right.
     *
     * Cached per sprite — codepoints are allocated once, so repeated calls
     * return the same regions rather than leaking glyph space.
     *
     * Returns empty when [entry] carries no nine-slice metadata.
     *
     * A border can legitimately zero out a row or column (e.g. a tab sprite
     * with no bottom border), which yields a region with `w == 0` or
     * `h == 0`. Regions are not filtered here — callers that crop pixels
     * from a source image (see [SpriteSliceProvider.requestSlices]) must
     * skip non-positive regions themselves, since [java.awt.image.BufferedImage.getSubimage]
     * rejects a zero or negative width/height.
     */
    fun ninePatch(entry: SpriteEntry): List<SliceRegion> {
        entry.nineSlice ?: return emptyList()
        return cache.getOrPut(entry.id) { computeNinePatch(entry) }
    }

    private fun computeNinePatch(entry: SpriteEntry): List<SliceRegion> {
        val slice = entry.nineSlice ?: return emptyList()

        val xs = listOf(0, slice.left, entry.width - slice.right)
        val widths = listOf(
            slice.left,
            entry.width - slice.left - slice.right,
            slice.right
        )
        val ys = listOf(0, slice.top, entry.height - slice.bottom)
        val heights = listOf(
            slice.top,
            entry.height - slice.top - slice.bottom,
            slice.bottom
        )

        val regions = ArrayList<SliceRegion>(9)
        for (row in 0..2) {
            for (col in 0..2) {
                regions += SliceRegion(
                    entry = entry,
                    x = xs[col], y = ys[row],
                    w = widths[col], h = heights[row],
                    codepoint = SpriteGlyphs.allocateSlice()
                )
            }
        }
        return regions
    }
}

/**
 * Emits cropped slice textures and their font providers.
 *
 * This is the only sprite mechanism that writes image bytes into the pack. It
 * is bounded — 37 vanilla sprites carry nine-slice metadata and they are tiny
 * (`button.png` is 1699 bytes) — and slices are generated on demand rather
 * than eagerly.
 *
 * ### Known gap: no runtime source image on a dedicated server
 *
 * [requestSlices] requires the caller to hand it the sprite's decoded source
 * PNG. On a client that is trivial — the vanilla texture is already loaded.
 * **On a dedicated Minecraft server there is currently no way to obtain
 * that image at runtime.** Vanilla GUI sprite textures ship only inside the
 * client jar; the server process never reads that jar and has no
 * client-texture-manager equivalent to ask.
 *
 * This is a known, deliberate gap, not an oversight:
 *  1. The caller is responsible for supplying [source] — this function does
 *     not, and cannot, load it itself.
 *  2. A dedicated server cannot obtain vanilla client textures at runtime.
 *     There is no code path today that can call [requestSlices] for a
 *     vanilla sprite from server-only code.
 *  3. The intended eventual fix is to generate slice textures at *build*
 *     time, alongside the sprite index — the same way
 *     `io.schemat.displaykit.pack.gen.SpriteIndexGenerator` already reads a
 *     client jar offline to produce the committed `sprites.json`. A future
 *     generator would crop the 37 nine-slice sprites once, at index-build
 *     time, and commit the resulting PNGs, so the server never needs the
 *     client jar at runtime.
 *
 * Build-time generation is **not implemented here** — it is out of scope for
 * this task, whose deliverable is the testable [SpriteSlicer] primitive and
 * the geometry it produces. Do not treat the missing runtime path as a bug to
 * silently patch; any fix belongs in a build-time generator, not in a new
 * runtime image source.
 */
object SpriteSliceProvider : AssetProvider {

    private val pending = LinkedHashMap<Int, Pair<SliceRegion, BufferedImage>>()

    /**
     * Queue [entry]'s nine slices, cropping from [source] — the sprite's
     * texture as loaded from the client jar or a resource pack.
     *
     * See the class KDoc above: on a dedicated server there is presently no
     * runtime path that can supply [source] for a vanilla sprite. Callers
     * must obtain the image themselves; this function does not attempt to.
     */
    fun requestSlices(entry: SpriteEntry, source: BufferedImage) {
        for (region in SpriteSlicer.ninePatch(entry)) {
            if (region.w <= 0 || region.h <= 0) continue
            val crop = source.getSubimage(region.x, region.y, region.w, region.h)
            pending[region.codepoint] = region to crop
        }
    }

    fun clear() = pending.clear()

    override fun contributeAssets(builder: PackBuilder) {
        if (pending.isEmpty()) return

        val providers = JsonArray()
        for ((codepoint, pair) in pending) {
            val (region, image) = pair
            val name = "${region.entry.id.atlas}_" +
                region.entry.id.sprite.replace('/', '_') +
                "_${region.x}_${region.y}"

            builder.addImage("assets/displaykit/textures/font/slices/$name.png", image)

            providers.add(JsonObject().apply {
                addProperty("type", "bitmap")
                addProperty("file", "displaykit:font/slices/$name.png")
                addProperty("height", region.h)
                addProperty("ascent", region.h)
                add("chars", JsonArray().apply {
                    add(String(Character.toChars(codepoint)))
                })
            })
        }

        builder.addJson(
            "assets/displaykit/font/sprite_slices.json",
            JsonObject().apply { add("providers", providers) }.toString()
        )
    }
}
