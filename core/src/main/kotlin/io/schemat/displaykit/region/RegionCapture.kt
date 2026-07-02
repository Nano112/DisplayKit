package io.schemat.displaykit.region

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3i

/**
 * Axis-aligned bounding box for a region.
 */
data class BoundingBox(
    val min: Vec3i,
    val max: Vec3i
) {
    val width: Int get() = max.x - min.x + 1
    val height: Int get() = max.y - min.y + 1
    val depth: Int get() = max.z - min.z + 1
    val volume: Int get() = width * height * depth

    /**
     * Center of the bounding box.
     */
    val center: Vec3d get() = Vec3d(
        (min.x + max.x) / 2.0,
        (min.y + max.y) / 2.0,
        (min.z + max.z) / 2.0
    )

    companion object {
        fun fromPoints(p1: Vec3i, p2: Vec3i): BoundingBox {
            return BoundingBox(
                min = Vec3i(
                    minOf(p1.x, p2.x),
                    minOf(p1.y, p2.y),
                    minOf(p1.z, p2.z)
                ),
                max = Vec3i(
                    maxOf(p1.x, p2.x),
                    maxOf(p1.y, p2.y),
                    maxOf(p1.z, p2.z)
                )
            )
        }
    }
}

/**
 * Immutable snapshot of a captured block region.
 *
 * Block positions are stored relative to the region origin (min corner),
 * allowing the capture to be placed anywhere in the world.
 *
 * @param blocks Map of relative positions to captured blocks (excludes air)
 * @param bounds Bounding box of the original region
 * @param origin World position where the region was captured (min corner)
 */
data class RegionCapture(
    val blocks: Map<Vec3i, CapturedBlock>,
    val bounds: BoundingBox,
    val origin: Vec3d
) {
    /**
     * Number of non-air blocks in this capture.
     */
    val blockCount: Int get() = blocks.size

    /**
     * Check if this capture is empty (all air).
     */
    val isEmpty: Boolean get() = blocks.isEmpty()

    /**
     * Get the captured block at a relative position.
     */
    operator fun get(relativePos: Vec3i): CapturedBlock? = blocks[relativePos]

    /**
     * Iterate over all captured blocks with their relative positions.
     */
    inline fun forEach(action: (Vec3i, CapturedBlock) -> Unit) {
        blocks.forEach { (pos, block) -> action(pos, block) }
    }

    companion object {
        /**
         * Create an empty capture with the given bounds.
         */
        fun empty(bounds: BoundingBox, origin: Vec3d): RegionCapture {
            return RegionCapture(emptyMap(), bounds, origin)
        }
    }
}
