package io.schemat.displaykit.surface

import io.schemat.displaykit.sprite.SpriteEntry
import kotlin.math.ceil

/**
 * One crop of a nine-sliced sprite, placed at a position in the target rect.
 * Coordinates are relative to the frame's own top-left.
 */
data class PlacedRegion(
    val srcX: Int, val srcY: Int, val srcW: Int, val srcH: Int,
    val dstX: Int, val dstY: Int
)

/**
 * Works out where every crop of a nine-sliced sprite goes when the sprite is
 * stretched to an arbitrary size.
 *
 * Edges and centre TILE rather than stretch, which is what keeps pixel art
 * crisp. Because a tiled run rarely divides evenly, and a partial tile would
 * need a narrower crop than the nine we generate at build time, the final tile
 * along each axis is placed flush against the far edge and OVERLAPS its
 * neighbour. On a uniform border that overlap is invisible.
 *
 * That trick needs at least one whole tile to fit, which is why the minimum
 * frame size is the sprite's own size.
 *
 * Regions are emitted centre first, then edges, then corners, so later draws
 * paint over earlier ones and no overlap shows at a corner.
 */
object NineSliceLayout {

    /** Smallest frame this sprite can render: its own dimensions. */
    fun minimumSize(entry: SpriteEntry): Pair<Int, Int> = entry.width to entry.height

    /**
     * The smallest frame size at least `minWidth` x `minHeight` whose interior
     * is a whole number of centre tiles on both axes, so [regionsFor] emits no
     * overlapping tile at that size.
     *
     * For a sprite with border `l/t/r/b` and centre `cw x ch`, that is
     * `l + r + ceil((minWidth - l - r) / cw) * cw` horizontally, and the same
     * shape vertically. If an axis has no centre tile at all (the border
     * consumes the whole sprite on that axis), the sprite can't grow on that
     * axis without overlap, so this returns the sprite's own size for it.
     *
     * Prefer sizing frames through this rather than picking numbers by hand:
     * see the overlap warning on [regionsFor].
     */
    /**
     * True if [entry] tiles [width] x [height] with no tile drawn twice.
     *
     * The centre and edge crops are fixed images, so a target that is not
     * `border + k * tile` cannot be filled exactly. The tiler covers the
     * shortfall by placing its LAST tile flush against the far edge, which
     * overlaps the previous one -- and two copies of a patterned tile offset
     * from each other read as diagonal hatching or dashes, not as a seam.
     *
     * That is what a 30px-tall `widget/tab` did: its centre tile is 22, the
     * interior 28, so tiles landed at y=2 and y=8 and moired across the
     * middle of every unselected tab.
     *
     * Chrome should be sized through [exactSizeFor]; this is for asserting
     * that it was.
     */
    fun tilesEvenly(entry: SpriteEntry, width: Int, height: Int): Boolean {
        val s = entry.nineSlice ?: return true
        val cw = entry.width - s.left - s.right
        val ch = entry.height - s.top - s.bottom
        if (cw <= 0 || ch <= 0) return true
        val innerW = width - s.left - s.right
        val innerH = height - s.top - s.bottom
        if (innerW < 0 || innerH < 0) return false
        return innerW % cw == 0 && innerH % ch == 0
    }

    fun exactSizeFor(entry: SpriteEntry, minWidth: Int, minHeight: Int): Pair<Int, Int> {
        val s = entry.nineSlice ?: return maxOf(minWidth, entry.width) to maxOf(minHeight, entry.height)
        val cw = entry.width - s.left - s.right
        val ch = entry.height - s.top - s.bottom
        val w = exactAxis(minWidth, entry.width, s.left, s.right, cw)
        val h = exactAxis(minHeight, entry.height, s.top, s.bottom, ch)
        return w to h
    }

    /** One axis of [exactSizeFor]: round `requested` up to `near + far + k*tile`. */
    private fun exactAxis(requested: Int, minSize: Int, near: Int, far: Int, tile: Int): Int {
        if (tile <= 0) return minSize
        val target = maxOf(requested, minSize)
        val interior = target - near - far
        val tiles = ceil(interior.toDouble() / tile).toInt().coerceAtLeast(1)
        return near + far + tiles * tile
    }

    /**
     * Where every crop of a nine-sliced sprite goes when the sprite is
     * stretched to [width] x [height].
     *
     * If `width`/`height` do not tile exactly (see [exactSizeFor]), the final
     * tile along that axis is placed flush against the far edge and OVERLAPS
     * its neighbour rather than being cropped — every glyph in a surface is
     * coplanar, so two overlapping opaque tiles fight for the same depth and
     * can shimmer. Pass a size from [exactSizeFor] to avoid that entirely.
     */
    fun regionsFor(entry: SpriteEntry, width: Int, height: Int): List<PlacedRegion> {
        val s = entry.nineSlice ?: return emptyList()
        require(width >= entry.width && height >= entry.height) {
            "A ${entry.id} frame must be at least ${entry.width}x${entry.height} " +
                "(asked for ${width}x$height). Tiling needs one whole tile to fit; " +
                "a smaller frame would require a crop that is not generated."
        }

        val l = s.left; val t = s.top; val r = s.right; val b = s.bottom
        val cw = entry.width - l - r      // centre source width
        val ch = entry.height - t - b     // centre source height
        val innerLeft = l; val innerRight = width - r
        val innerTop = t; val innerBottom = height - b

        val out = ArrayList<PlacedRegion>(16)

        /** Tile positions along one axis: a full grid, with the last flush to `end`. */
        fun steps(start: Int, end: Int, tile: Int): List<Int> {
            if (tile <= 0 || end <= start) return emptyList()
            val positions = ArrayList<Int>()
            var p = start
            while (p + tile < end) { positions.add(p); p += tile }
            positions.add(end - tile)   // final tile, flush right/bottom
            return positions
        }

        val xs = steps(innerLeft, innerRight, cw)
        val ys = steps(innerTop, innerBottom, ch)

        // centre
        if (cw > 0 && ch > 0) for (y in ys) for (x in xs) {
            out += PlacedRegion(l, t, cw, ch, x, y)
        }
        // top / bottom edges
        if (cw > 0 && t > 0) for (x in xs) out += PlacedRegion(l, 0, cw, t, x, 0)
        if (cw > 0 && b > 0) for (x in xs) out += PlacedRegion(l, entry.height - b, cw, b, x, height - b)
        // left / right edges
        if (ch > 0 && l > 0) for (y in ys) out += PlacedRegion(0, t, l, ch, 0, y)
        if (ch > 0 && r > 0) for (y in ys) out += PlacedRegion(entry.width - r, t, r, ch, width - r, y)
        // corners last, so they cover any edge overlap
        if (l > 0 && t > 0) out += PlacedRegion(0, 0, l, t, 0, 0)
        if (r > 0 && t > 0) out += PlacedRegion(entry.width - r, 0, r, t, width - r, 0)
        if (l > 0 && b > 0) out += PlacedRegion(0, entry.height - b, l, b, 0, height - b)
        if (r > 0 && b > 0) out += PlacedRegion(entry.width - r, entry.height - b, r, b, width - r, height - b)

        return out
    }
}
