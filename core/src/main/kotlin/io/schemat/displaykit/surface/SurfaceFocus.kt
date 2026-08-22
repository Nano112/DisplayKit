package io.schemat.displaykit.surface

import io.schemat.displaykit.surface.layout.SurfaceNode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-viewer pointer, hover and grab state.
 *
 * Kept out of [SurfaceHost] because a viewer has one pointer across all their
 * surfaces, while a host is one window.
 *
 * Accessed from both the server tick thread and the netty packet thread (a
 * hotbar-scroll mixin consults [isScrollArmed] before the packet is rescheduled
 * onto the main thread, and a chat mixin consults [isTextArmed] the same way),
 * which is why [Entry]'s fields are volatile.
 */
object SurfaceFocus {

    data class State(
        val hoveredId: String? = null,
        val grabbedId: String? = null,
        val scrollArmed: Boolean = false,
        val textArmed: Boolean = false
    )

    private class Entry {
        // @Volatile on all: these are written from the server tick thread
        // and read from the netty thread, where a packet mixin decides whether to
        // intercept hotbar scrolling or chat. ConcurrentHashMap only orders the map
        // operations, not in-place mutation of the value it hands back, so
        // without this a reader can see arbitrarily stale state.
        @Volatile var hovered: SurfaceNode? = null
        @Volatile var grabbed: SurfaceNode? = null
        @Volatile var scrollArmed: Boolean = false
        @Volatile var textArmed: Boolean = false
    }

    private val entries = ConcurrentHashMap<UUID, Entry>()

    private fun entry(player: UUID) = entries.computeIfAbsent(player) { Entry() }

    fun state(player: UUID): State {
        val e = entries[player] ?: return State()
        return State(e.hovered?.id, e.grabbed?.id, e.scrollArmed, e.textArmed)
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
        e.scrollArmed = node != null && hasMarkedAncestor<SurfaceNodeMarker.Scrollable>(node)
        e.textArmed = node != null && hasMarkedAncestor<SurfaceNodeMarker.TextCapturing>(node)
        return changed
    }

    /** Pointer left every surface. Does NOT drop an active grab. */
    fun pointerLost(player: UUID): Boolean {
        val e = entries[player] ?: return false
        val changed = e.hovered != null
        e.hovered = null
        e.scrollArmed = false
        e.textArmed = false
        return changed
    }

    /** True while the pointer sits inside something scrollable. */
    fun isScrollArmed(player: UUID): Boolean = entries[player]?.scrollArmed == true

    /** True while the pointer sits inside something that captures chat (see a terminal widget). */
    fun isTextArmed(player: UUID): Boolean = entries[player]?.textArmed == true

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

    /**
     * True if [node] or any of its ancestors implements marker type [T].
     *
     * One walk parameterised by the marker, shared by [isScrollArmed] and
     * [isTextArmed]: a node arms whichever markers its own ancestry actually
     * carries, and arming one must not imply the other just because they
     * happen to share this walk.
     */
    private inline fun <reified T> hasMarkedAncestor(node: SurfaceNode): Boolean {
        var n: SurfaceNode? = node
        while (n != null) {
            if (n is T) return true
            n = n.parent
        }
        return false
    }
}
