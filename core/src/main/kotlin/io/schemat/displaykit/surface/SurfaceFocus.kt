package io.schemat.displaykit.surface

import io.schemat.displaykit.surface.layout.SurfaceNode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

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

    private val logger = Logger.getLogger("DisplayKit/SurfaceFocus")

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
    /**
     * Notified whenever a viewer's focus is dropped, so platform-side state
     * keyed on the same viewer can be torn down with it.
     *
     * Focus is the authoritative "this viewer is interacting with a surface"
     * signal, and things hang off it that core cannot reach: the Fabric
     * hotbar-scroll capture remembers the player's real selected slot for the
     * whole time the pointer sits inside a scrollable. Without this hook that
     * memory outlived the interaction -- a viewer who disconnected while
     * hovering a scrollable left an entry keyed by their UUID forever, and on
     * reconnect their first scroll was measured against a slot from the
     * previous session.
     *
     * Registering here rather than in each window is deliberate: the same
     * per-window duplication produced three separate defects in this
     * subsystem. One registration covers every surface that will ever exist.
     */
    fun interface FocusClearedListener {
        fun onFocusCleared(player: UUID)
    }

    private val clearedListeners = java.util.concurrent.CopyOnWriteArrayList<FocusClearedListener>()

    /** Register [listener]; it is called on every [clear]. Idempotent per instance. */
    fun onFocusCleared(listener: FocusClearedListener) {
        if (listener !in clearedListeners) clearedListeners += listener
    }

    /** Drop [listener]. Test seam, and for a platform tearing itself down. */
    fun removeFocusClearedListener(listener: FocusClearedListener) {
        clearedListeners.remove(listener)
    }

    fun clear(player: UUID) {
        entries.remove(player)
        // After removal, so a listener that inspects focus sees it already
        // gone rather than a half-torn-down state. A throwing listener must
        // not strand the others or abort the caller's teardown.
        for (l in clearedListeners) {
            try {
                l.onFocusCleared(player)
            } catch (t: Throwable) {
                logger.warning("focus-cleared listener failed for $player: $t")
            }
        }
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
