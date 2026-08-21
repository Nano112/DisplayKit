package io.schemat.displaykit.surface

import io.schemat.displaykit.surface.layout.SurfaceNode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-viewer pointer, hover and grab state.
 *
 * Kept out of [SurfaceHost] because a viewer has one pointer across all their
 * surfaces, while a host is one window.
 */
object SurfaceFocus {

    data class State(
        val hoveredId: String? = null,
        val grabbedId: String? = null,
        val scrollArmed: Boolean = false
    )

    private class Entry {
        var hovered: SurfaceNode? = null
        var grabbed: SurfaceNode? = null
        var scrollArmed: Boolean = false
    }

    private val entries = ConcurrentHashMap<UUID, Entry>()

    private fun entry(player: UUID) = entries.computeIfAbsent(player) { Entry() }

    fun state(player: UUID): State {
        val e = entries[player] ?: return State()
        return State(e.hovered?.id, e.grabbed?.id, e.scrollArmed)
    }

    /**
     * Record the node under the pointer. Returns true if it CHANGED, which is
     * the caller's signal to fire enter/exit and repaint — repainting every
     * tick would cost ~140 metadata packets a second per viewer.
     */
    fun pointerAt(player: UUID, node: SurfaceNode?): Boolean {
        val e = entry(player)
        val changed = e.hovered?.id != node?.id
        e.hovered = node
        e.scrollArmed = node != null && hasScrollableAncestor(node)
        return changed
    }

    /** Pointer left every surface. Does NOT drop an active grab. */
    fun pointerLost(player: UUID): Boolean {
        val e = entries[player] ?: return false
        val changed = e.hovered != null
        e.hovered = null
        e.scrollArmed = false
        return changed
    }

    /** True while the pointer sits inside something scrollable. */
    fun isScrollArmed(player: UUID): Boolean = entries[player]?.scrollArmed == true

    fun hovered(player: UUID): SurfaceNode? = entries[player]?.hovered

    /** Begin a grab. Returns false when the node is not grabbable. */
    fun grab(player: UUID, node: SurfaceNode): Boolean {
        if (node.onGrabMove == null) return false
        entry(player).grabbed = node
        return true
    }

    fun grabbed(player: UUID): SurfaceNode? = entries[player]?.grabbed

    fun release(player: UUID) {
        entries[player]?.grabbed = null
    }

    /** Drop all state — surface closed, or the player disconnected. */
    fun clear(player: UUID) {
        entries.remove(player)
    }

    private fun hasScrollableAncestor(node: SurfaceNode): Boolean {
        var n: SurfaceNode? = node
        while (n != null) {
            if (n is SurfaceNodeMarker.Scrollable) return true
            n = n.parent
        }
        return false
    }
}
