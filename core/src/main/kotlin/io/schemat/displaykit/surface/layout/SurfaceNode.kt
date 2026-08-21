package io.schemat.displaykit.surface.layout

import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.SurfaceEvent

/**
 * A node in a surface's pixel-space layout tree.
 *
 * The tree exists for two reasons: so widgets stop hardcoding absolute rects,
 * and so events have a parent chain to bubble along. A flat region list cannot
 * express "this cell ignored the scroll, so the pane containing it should take
 * it" — see [io.schemat.displaykit.surface.SurfaceEvents].
 */
interface SurfaceNode {
    val id: String
    var parent: SurfaceNode?
    val children: List<SurfaceNode>
    var layoutResult: PxLayoutResult?

    var padding: PxPadding
    /** Fixed size, or null to size to content. */
    var width: Int?
    var height: Int?
    /** Integer weight for leftover main-axis space. Zero means "do not grow". */
    var flexGrow: Int

    /** Returns CONSUMED to stop bubbling. */
    var onEvent: ((SurfaceEvent) -> EventResult)?

    /**
     * Called each tick while this node is grabbed, with the absolute canvas
     * point. Set this to make a node draggable; see SurfaceFocus.
     */
    var onGrabMove: ((Int, Int) -> Unit)?

    fun measure(c: PxConstraints): PxSize
    fun place(offset: PxOffset)
    fun addChild(child: SurfaceNode)
    fun clearChildren()

    /** Absolute canvas rect. Only valid after [place]. */
    fun rect(): Rect

    /** Deepest node whose rect contains the point, or null if outside this node. */
    fun hitTest(x: Int, y: Int): SurfaceNode?
}

abstract class BaseSurfaceNode(override val id: String) : SurfaceNode {
    override var parent: SurfaceNode? = null
    protected val _children = mutableListOf<SurfaceNode>()
    override val children: List<SurfaceNode> get() = _children
    override var layoutResult: PxLayoutResult? = null
    override var padding: PxPadding = PxPadding.Zero
    override var width: Int? = null
    override var height: Int? = null
    override var flexGrow: Int = 0
    override var onEvent: ((SurfaceEvent) -> EventResult)? = null
    override var onGrabMove: ((Int, Int) -> Unit)? = null

    /**
     * Subclass hook: measure own content, already constrained.
     *
     * A container measures its OWN children here, with the constraints it has
     * decided for each. The base class deliberately does not cascade: a cascade
     * after this call would overwrite per-child sizing (FlexNode's, for one)
     * with loosened natural sizes.
     */
    protected abstract fun measureSelf(c: PxConstraints): PxSize

    override fun measure(c: PxConstraints): PxSize {
        val fixed = PxConstraints(
            minW = width ?: c.minW, maxW = width ?: c.maxW,
            minH = height ?: c.minH, maxH = height ?: c.maxH
        )
        val size = measureSelf(fixed)
        layoutResult = PxLayoutResult(layoutResult?.offset ?: PxOffset.Zero, size)
        return size
    }

    override fun place(offset: PxOffset) {
        layoutResult = PxLayoutResult(offset, layoutResult?.size ?: PxSize.Zero)
    }

    override fun addChild(child: SurfaceNode) {
        child.parent = this
        _children.add(child)
    }

    override fun clearChildren() {
        _children.forEach { it.parent = null }
        _children.clear()
    }

    /**
     * Absolute offset, accumulated up the parent chain.
     *
     * Walks the [SurfaceNode] interface rather than casting to
     * [BaseSurfaceNode]: `addChild` accepts the interface, so a parent is not
     * guaranteed to be a [BaseSurfaceNode], and a cast here would fail at
     * rect()/hitTest() time far from whatever attached it.
     */
    private fun absoluteOffset(): PxOffset {
        var acc = PxOffset.Zero
        var node: SurfaceNode? = this
        while (node != null) {
            acc += node.layoutResult?.offset ?: PxOffset.Zero
            node = node.parent
        }
        return acc
    }

    override fun rect(): Rect {
        val o = absoluteOffset()
        val s = layoutResult?.size ?: PxSize.Zero
        return Rect(o.x, o.y, s.w, s.h)
    }

    override fun hitTest(x: Int, y: Int): SurfaceNode? {
        if (!rect().contains(x, y)) return null
        // Reversed: later children are drawn on top, so they take the hit.
        for (child in _children.asReversed()) {
            child.hitTest(x, y)?.let { return it }
        }
        return this
    }
}
