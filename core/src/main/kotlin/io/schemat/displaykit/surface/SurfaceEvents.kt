package io.schemat.displaykit.surface

import io.schemat.displaykit.surface.layout.SurfaceNode

/**
 * Hit-test-then-bubble dispatch.
 *
 * The deepest node under the point gets first refusal; anything it does not
 * consume walks up the parent chain. This is the whole reason surfaces have a
 * tree rather than a flat region list: a flat list cannot express "the grid
 * cell ignored this scroll, so the pane containing it should take it".
 */
object SurfaceEvents {

    /**
     * Dispatch [event] into [root], returning the node that consumed it, or
     * null if nothing did (including when the point is outside [root]).
     *
     * [SurfaceEvent.PointerEnter] and [SurfaceEvent.PointerExit] describe a
     * single node's own state, so they are delivered only to [target] when one
     * is given, and never bubble.
     */
    fun dispatch(
        root: SurfaceNode,
        event: SurfaceEvent,
        target: SurfaceNode? = null
    ): SurfaceNode? {
        if (event is SurfaceEvent.PointerEnter || event is SurfaceEvent.PointerExit) {
            val n = target ?: return null
            n.onEvent?.invoke(event)
            return n
        }
        var node: SurfaceNode? = target ?: root.hitTest(event.x, event.y)
        while (node != null) {
            val handler = node.onEvent
            if (handler != null && handler(event) == EventResult.CONSUMED) return node
            node = node.parent
        }
        return null
    }
}
