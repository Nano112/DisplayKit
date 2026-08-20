package io.schemat.displaykit.layout

import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGeometry

/**
 * A sprite as a layout node with a real intrinsic size.
 *
 * This is what the index buys the layout system: a sprite reports the world
 * dimensions it will actually occupy, so it composes in [FlexNode] like any
 * other node instead of needing hand-computed sizes at every call site.
 */
class SpriteNode(
    id: String,
    entry: SpriteEntry,
    scale: Float = 1f
) : BaseLayoutNode(id) {

    var entry: SpriteEntry = entry
    var scale: Float = scale

    override fun measure(constraints: Constraints): Size {
        val c = applyOwnConstraints(constraints)
        val intrinsic = SpriteGeometry.worldSize(entry, scale)

        val width = if (c.hasFixedWidth) c.maxWidth else c.constrainWidth(intrinsic.x)
        val height = if (c.hasFixedHeight) c.maxHeight else c.constrainHeight(intrinsic.y)

        val size = finalizeSize(Size(width, height), c)
        layoutResult = LayoutResult(Offset.Zero, size)
        return size
    }

    override fun addChild(child: LayoutNode) {
        throw UnsupportedOperationException("SpriteNode cannot have children")
    }
}
