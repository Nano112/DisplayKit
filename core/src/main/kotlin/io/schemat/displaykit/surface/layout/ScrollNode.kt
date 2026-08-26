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
 *
 * Open so a caller can attach an additional marker interface to just ONE use
 * site (e.g. `class TerminalScrollNode(id: String) : ScrollNode(id),
 * SurfaceNodeMarker.TextCapturing`) without arming that marker for every
 * scroll pane in the codebase -- see `io.schemat.displaykit.surface.terminal`.
 */
open class ScrollNode(id: String) : BaseSurfaceNode(id), SurfaceNodeMarker.Scrollable, ChildViewport {

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
        // Floor the viewport to a whole number of steps.
        //
        // Every scroll position must keep the content on the same vertical
        // phase, because a glyph's ascent is baked per y: land a row half a
        // text row off and every sprite in it becomes a new variant, which
        // grows the resource pack and makes every client re-download.
        //
        // Notches already move by stepPx, so they preserve phase. The ENDS
        // did not: maxScroll is `contentH - viewportH`, and an arbitrary
        // viewport height leaves that off-grid, so scrolling all the way to
        // the bottom shifted every row and reloaded the pack -- reported
        // exactly that way, and introduced by pinning the extremes so the
        // last row could be reached at all.
        //
        // Flooring costs at most stepPx-1 pixels of visible height and makes
        // maxScroll a multiple of stepPx whenever the content is, which is
        // the case for the uniform rows a scrolling list is built from.
        val step = stepPx.coerceAtLeast(1)
        viewportH = inner.maxH - (inner.maxH % step)
        // A retained list can shrink while open. Never leave its old offset
        // beyond the new extent, which would paint an apparently empty page.
        scrollPx = scrollPx.coerceAtMost(maxScroll())
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

    /**
     * Set the offset directly, e.g. from a dragged scrollbar thumb.
     *
     * Snaps to a [stepPx] boundary, for the same reason [scrollBy] moves in
     * steps: only whole children are emitted, so an offset between rows leaves
     * nothing flush with the viewport edge and blanks it.
     */
    fun scrollTo(px: Int): Boolean {
        val step = stepPx.coerceAtLeast(1)
        val max = maxScroll()
        // Both ENDS must be reachable even when they are not on a step
        // boundary. Snapping first and clamping second cannot get there:
        // asking for maxScroll 701 with a step of 10 snaps to 700 and the
        // clamp has nothing to lift, so the last row of a terminal stays
        // permanently just out of view. Every scroll UI pins its extremes.
        val target = when {
            px >= max -> max
            px <= 0 -> 0
            else -> (((px + step / 2) / step) * step).coerceIn(0, max)
        }
        if (target == scrollPx) return false
        scrollPx = target
        place(layoutResult?.offset ?: PxOffset.Zero)
        return true
    }

    /** Children wholly inside the viewport — the only ones safe to paint. */
    override fun visibleChildren(): List<SurfaceNode> {
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
