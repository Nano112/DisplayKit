package io.schemat.displaykit.surface.layout

import io.schemat.displaykit.surface.SurfaceNodeMarker

/**
 * A vertically scrolling viewport.
 *
 * Children are stacked top to bottom at their natural heights; the viewport
 * shows a window into that column. Presence of this node in a hit node's
 * ancestry is what arms hotbar scroll capture — see SurfaceFocus.
 *
 * **Whole children only.** The canvas has no clipping, so a partially visible
 * child would draw straight out through the window frame. [visibleChildren]
 * therefore emits only children fully inside the viewport, and scrolling moves
 * in [stepPx] increments so rows stay aligned with the viewport edge. Set
 * [stepPx] to your row pitch.
 */
class ScrollNode(id: String) : BaseSurfaceNode(id), SurfaceNodeMarker.Scrollable {

    /** Current scroll offset in pixels from the top of the content. */
    var scrollPx: Int = 0
        private set

    /** Pixels per scroll notch. Set to the row pitch to keep rows aligned. */
    var stepPx: Int = 1

    private var viewportH: Int = 0
    private var contentH: Int = 0

    override fun measureSelf(c: PxConstraints): PxSize {
        val inner = c.deflate(padding)
        var y = 0
        for (child in _children) {
            val cs = child.measure(PxConstraints(inner.minW, inner.maxW, 0, inner.maxH))
            y += cs.h
        }
        contentH = y
        viewportH = inner.maxH
        return c.constrain(PxSize(inner.maxW + padding.horizontal, inner.maxH + padding.vertical))
    }

    override fun place(offset: PxOffset) {
        super.place(offset)
        var y = -scrollPx
        for (child in _children) {
            child.place(PxOffset(padding.left, padding.top + y))
            y += child.layoutResult?.size?.h ?: 0
        }
    }

    /** Largest legal [scrollPx]. Zero when the content fits. */
    fun maxScroll(): Int = (contentH - viewportH).coerceAtLeast(0)

    /**
     * Scroll by [delta] notches. Returns true if [scrollPx] actually changed,
     * so a caller can report the event unconsumed at the ends of the range and
     * let it bubble.
     */
    fun scrollBy(delta: Int): Boolean {
        val target = (scrollPx + delta * stepPx).coerceIn(0, maxScroll())
        if (target == scrollPx) return false
        scrollPx = target
        place(layoutResult?.offset ?: PxOffset.Zero)
        return true
    }

    /** Set the offset directly, e.g. from a dragged scrollbar thumb. */
    fun scrollTo(px: Int): Boolean {
        val target = px.coerceIn(0, maxScroll())
        if (target == scrollPx) return false
        scrollPx = target
        place(layoutResult?.offset ?: PxOffset.Zero)
        return true
    }

    /** Children wholly inside the viewport — the only ones safe to paint. */
    fun visibleChildren(): List<SurfaceNode> {
        val top = rect().y + padding.top
        val bottom = top + viewportH
        return _children.filter { c ->
            val r = c.rect()
            r.y >= top && r.bottom <= bottom
        }
    }

    override fun hitTest(x: Int, y: Int): SurfaceNode? {
        if (!rect().contains(x, y)) return null
        for (child in visibleChildren().asReversed()) {
            child.hitTest(x, y)?.let { return it }
        }
        return this
    }
}
