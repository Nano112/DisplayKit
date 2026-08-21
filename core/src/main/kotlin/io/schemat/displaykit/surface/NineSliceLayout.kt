package io.schemat.displaykit.surface

import io.schemat.displaykit.sprite.SpriteEntry

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
