package io.schemat.displaykit.surface.layout

/**
 * Integer pixel geometry for surface layout.
 *
 * Deliberately integer throughout. Glyph positions are integers, and every
 * float rounding boundary this subsystem has introduced between layout and
 * glyph placement has produced a visible defect. See the spec's "why the
 * pixel layout tree is duplicated" section.
 */
data class PxSize(val w: Int, val h: Int) {
    companion object { val Zero = PxSize(0, 0) }
}

data class PxOffset(val x: Int, val y: Int) {
    operator fun plus(other: PxOffset) = PxOffset(x + other.x, y + other.y)
    companion object { val Zero = PxOffset(0, 0) }
}

data class PxPadding(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    val horizontal: Int get() = left + right
    val vertical: Int get() = top + bottom

    companion object {
        val Zero = PxPadding()
        fun all(n: Int) = PxPadding(n, n, n, n)
        fun symmetric(h: Int, v: Int) = PxPadding(h, v, h, v)
    }
}

data class PxConstraints(
    val minW: Int,
    val maxW: Int,
    val minH: Int,
    val maxH: Int
) {
    fun constrain(size: PxSize) = PxSize(
        size.w.coerceIn(minW, maxW),
        size.h.coerceIn(minH, maxH)
    )

    /** Same box shrunk by [p], never below zero. */
    fun deflate(p: PxPadding) = PxConstraints(
        minW = (minW - p.horizontal).coerceAtLeast(0),
        maxW = (maxW - p.horizontal).coerceAtLeast(0),
        minH = (minH - p.vertical).coerceAtLeast(0),
        maxH = (maxH - p.vertical).coerceAtLeast(0)
    )

    /** Drops the minimum, keeping the ceiling — for measuring content. */
    fun loosen() = PxConstraints(0, maxW, 0, maxH)

    companion object {
        fun exactly(w: Int, h: Int) = PxConstraints(w, w, h, h)
        fun upTo(w: Int, h: Int) = PxConstraints(0, w, 0, h)
    }
}

data class PxLayoutResult(val offset: PxOffset, val size: PxSize)
