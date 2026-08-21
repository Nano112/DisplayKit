package io.schemat.displaykit.surface.layout

import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.SurfacePainter

/**
 * A container that just applies padding around a single child.
 *
 * Sizes to its content and does not stretch its child: an exact constraint on
 * the box (as [io.schemat.displaykit.surface.Surface.layout] gives the root)
 * is loosened before it reaches the child, so the child keeps its own natural
 * size and only a [FlexNode] child expands, via its own `flexGrow`.
 */
class BoxNode(id: String) : BaseSurfaceNode(id) {
    override fun measureSelf(c: PxConstraints): PxSize {
        val inner = c.deflate(padding)
        val child = _children.firstOrNull() ?: return c.constrain(PxSize.Zero)
        val cs = child.measure(inner.loosen())
        return c.constrain(PxSize(cs.w + padding.horizontal, cs.h + padding.vertical))
    }

    override fun place(offset: PxOffset) {
        super.place(offset)
        _children.firstOrNull()?.place(PxOffset(padding.left, padding.top))
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
class WidgetNode(
    id: String,
    var intrinsic: PxSize = PxSize.Zero,
    var render: (SurfacePainter, Rect) -> Unit = { _, _ -> }
) : BaseSurfaceNode(id) {
    override fun measureSelf(c: PxConstraints): PxSize = c.constrain(intrinsic)

    fun paint(painter: SurfacePainter) = render(painter, rect())
}
