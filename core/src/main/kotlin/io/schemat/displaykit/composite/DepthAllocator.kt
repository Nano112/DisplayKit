package io.schemat.displaykit.composite

/**
 * One thing asking to be placed in depth.
 *
 * @param key Back-to-front ordering key. Elements sharing a key are
 *   COPLANAR and receive the same depth -- that is the whole point of the key,
 *   and getting it wrong is what tilted a window into a ramp (see
 *   [DepthAllocator]). A [Double] so a caller can slot something strictly
 *   between two existing layers without renumbering them, which is how
 *   `Surface.recordFrame` puts an occluding fill between a frame's corners and
 *   whatever sits above them.
 * @param thickness How much depth this element physically occupies, in blocks.
 *   Zero for anything flat -- a sprite glyph plane, a text display. A block
 *   display is a real volume and must declare its real depth, or the next
 *   layer forward is allocated *inside* it.
 */
data class DepthLayer(val key: Double, val thickness: Float = 0f) {
    init {
        require(key.isFinite()) { "depth key must be finite, got $key" }
        require(thickness.isFinite() && thickness >= 0f) {
            "thickness must be finite and non-negative, got $thickness"
        }
    }
}

/**
 * Decides where every element of a composite sits along the view axis.
 *
 * The single authority for depth across every medium: sprite-glyph planes,
 * standalone text displays, block displays and item displays all get their
 * offset from here. Mixing media in one UI is only possible if exactly one
 * thing decides their order, and this is it.
 *
 * ### Why this exists as its own component
 *
 * Depth has produced the two worst-looking defects in this subsystem, both
 * while there was still only ONE medium:
 *
 *  - **The wedge.** Depth was `elementOrdinal * step`. A ~120-element page
 *    therefore spanned 1.2 blocks front-to-back and read as a window tilted
 *    into a ramp rather than a flat panel. The fix was to step by *distinct
 *    key*, not by element: coplanar things must land on the same plane.
 *  - **Corner occlusion.** A nine-slice frame's flat fills had to sit
 *    strictly between the corner sprites and everything above them, or the
 *    occlusion became a coin flip.
 *
 * Adding block displays makes it strictly harder, because the old rule
 * silently assumes **every layer is infinitely thin**. A block display is a
 * real volume: place the next layer one `step` in front of a block that is
 * half a block deep and it is allocated *inside* it, and the client resolves
 * the overlap however it likes. So allocation advances by each group's real
 * thickness, and a flat plane simply declares a thickness of zero -- which
 * reduces exactly to the old behaviour.
 *
 * ### The rule
 *
 * Walk distinct keys back to front. Each distinct key gets one depth, the
 * running cursor. The cursor then advances past the thickest member of that
 * group, plus [separation] to keep two surfaces off each other's z-fighting
 * plane.
 *
 * Pure arithmetic over plain data, with no Minecraft types and no entities, so
 * the ordering can be asserted directly. Both defects above reached a client
 * because the logic that caused them was only reachable by rendering.
 */
object DepthAllocator {

    /**
     * Default gap between adjacent layers, in blocks.
     *
     * Matches `Surface.LAYER_Z_STEP`. Duplicated as a default rather than
     * imported so this component stays independent of the 2D surface stack --
     * 3D composites use it too, and they have no `Surface`.
     */
    const val DEFAULT_SEPARATION = 0.01f

    /**
     * Depth for every distinct key in [layers], back to front, starting at 0.
     *
     * Elements sharing a key share a depth. The result is keyed by the
     * ordering key rather than positional, so a caller holding N elements over
     * M distinct keys looks each one up directly.
     *
     * Input order is irrelevant: keys are sorted, so the same scene always
     * allocates the same depths regardless of the order it was built in.
     */
    fun allocate(
        layers: Iterable<DepthLayer>,
        separation: Float = DEFAULT_SEPARATION
    ): Map<Double, Float> {
        require(separation.isFinite() && separation >= 0f) {
            "separation must be finite and non-negative, got $separation"
        }
        // Thickest member wins: the group is coplanar at its front face, so
        // the next layer must clear the deepest thing in it.
        val thickestByKey = sortedMapOf<Double, Float>()
        for (l in layers) {
            thickestByKey.merge(l.key, l.thickness, ::maxOf)
        }

        val out = LinkedHashMap<Double, Float>(thickestByKey.size)
        var cursor = 0f
        for ((key, thickness) in thickestByKey) {
            out[key] = cursor
            cursor += thickness + separation
        }
        return out
    }

    /**
     * Front-to-back extent the allocation occupies, in blocks.
     *
     * A flat panel should be a small number. This is the assertion that
     * catches a wedge before anyone has to look at one: the trailing
     * [separation] is excluded because nothing is allocated in it.
     */
    fun totalDepth(
        layers: Iterable<DepthLayer>,
        separation: Float = DEFAULT_SEPARATION
    ): Float {
        val allocated = allocate(layers, separation)
        if (allocated.isEmpty()) return 0f
        val thickestByKey = HashMap<Double, Float>()
        for (l in layers) thickestByKey.merge(l.key, l.thickness, ::maxOf)
        val lastKey = allocated.keys.last()
        return allocated.getValue(lastKey) + (thickestByKey[lastKey] ?: 0f)
    }
}
