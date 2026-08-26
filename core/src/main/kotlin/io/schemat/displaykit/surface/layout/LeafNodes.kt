package io.schemat.displaykit.surface.layout

import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.SurfacePainter

/**
 * A container that stacks every child at the same padded origin.
 *
 * Sizes to its LARGEST child and does not stretch any of them: an exact
 * constraint on the box (as [io.schemat.displaykit.surface.Surface.layout]
 * gives the root) is loosened before it reaches each child, so every child
 * keeps its own natural size and only a [FlexNode] child expands, via its own
 * `flexGrow`. All children share one origin rather than being sequenced along
 * an axis -- that is what a [FlexNode] is for -- so a Box is how a background
 * sits behind foreground content: paint order already means later children
 * draw on top, matching the rest of the tree's z convention.
 */
class BoxNode(id: String) : BaseSurfaceNode(id) {
    override fun measureSelf(c: PxConstraints): PxSize {
        val inner = c.deflate(padding)
        if (_children.isEmpty()) return c.constrain(PxSize.Zero)
        var w = 0
        var h = 0
        for (child in _children) {
            val cs = child.measure(inner.loosen())
            if (cs.w > w) w = cs.w
            if (cs.h > h) h = cs.h
        }
        return c.constrain(PxSize(w + padding.horizontal, h + padding.vertical))
    }

    override fun place(offset: PxOffset) {
        super.place(offset)
        val at = PxOffset(padding.left, padding.top)
        _children.forEach { it.place(at) }
    }
}

/** Occupies leftover space and draws nothing. */
class SpacerNode(id: String, weight: Int = 1) : BaseSurfaceNode(id) {
    init { flexGrow = weight }
    override fun measureSelf(c: PxConstraints): PxSize = PxSize(c.minW, c.minH)
}

/**
 * A leaf that paints itself into the rect layout gave it.
 *
 * [render] receives the ABSOLUTE canvas rect, so widget code never computes
 * its own position — which is the point of the tree.
 */
open class WidgetNode(
    id: String,
    var intrinsic: PxSize = PxSize.Zero,
    var render: (SurfacePainter, Rect) -> Unit = { _, _ -> }
) : BaseSurfaceNode(id), SurfacePaintNode {
    override fun measureSelf(c: PxConstraints): PxSize = c.constrain(intrinsic)

    override fun paint(painter: SurfacePainter) = render(painter, rect())
}
