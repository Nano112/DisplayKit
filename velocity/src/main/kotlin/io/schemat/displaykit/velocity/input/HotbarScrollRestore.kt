package io.schemat.displaykit.velocity.input

import io.schemat.displaykit.surface.ScrollWrap
import io.schemat.displaykit.surface.SurfaceFocus
import io.schemat.displaykit.ui.InteractionRouter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Synthesises scroll events from hotbar selection changes, the proxy port of
 * fabric's HotbarScrollCapture.
 *
 * Capture only happens while the pointer is inside something scrollable, so
 * the hotbar behaves normally everywhere else. The player's slot is anchored
 * on first capture and restored when it ends.
 *
 * One proxy-specific wrinkle: a proxy has no inventory to read the current
 * slot from, so the anchor comes from tracking every held-item packet in
 * both directions. [onSlotChange] runs on the netty thread; the cancel
 * decision is made purely from [SurfaceFocus.isScrollArmed] (volatile-backed
 * and safe there), while resolving the scroll against the surface tree is
 * hopped onto the UI owner thread by the caller-supplied dispatchers.
 */
class HotbarScrollRestore(
    private val dispatchToUiThread: (Runnable) -> Unit,
    private val sendHeldSlot: (UUID, Int) -> Unit,
) {

    private val anchorSlot = ConcurrentHashMap<UUID, Int>()
    private val lastKnownSlot = ConcurrentHashMap<UUID, Int>()

    /**
     * Records a slot the client or the backend selected outside any capture,
     * keeping the anchor source accurate.
     */
    fun trackSlot(playerId: UUID, slot: Int) {
        lastKnownSlot[playerId] = slot
    }

    /**
     * Returns true if the packet should be cancelled: this was a scroll on a
     * surface rather than a genuine hotbar change.
     */
    fun onSlotChange(playerId: UUID, newSlot: Int): Boolean {
        if (!SurfaceFocus.isScrollArmed(playerId)) {
            // Not over a scrollable: the hotbar works normally, the change is
            // remembered as the new anchor source, and any capture ends.
            anchorSlot.remove(playerId)
            lastKnownSlot[playerId] = newSlot
            return false
        }

        val anchor = anchorSlot.computeIfAbsent(playerId) { lastKnownSlot[playerId] ?: newSlot }
        val delta = ScrollWrap.delta(anchor, newSlot)
        if (delta == 0) return false

        // Resolving walks the surface tree, may repaint, and sends metadata
        // packets, none of which is safe on the netty thread. The corrective
        // held-slot packet goes out from the same block, after the scroll
        // resolves.
        dispatchToUiThread(Runnable {
            InteractionRouter.getSurfaces(playerId).any { it.handleScroll(delta) }
            sendHeldSlot(playerId, anchor)
        })
        return true
    }

    /** Restore the anchored slot and stop capturing for this player. */
    fun release(playerId: UUID) {
        val anchor = anchorSlot.remove(playerId) ?: return
        sendHeldSlot(playerId, anchor)
    }

    fun forget(playerId: UUID) {
        anchorSlot.remove(playerId)
        lastKnownSlot.remove(playerId)
    }

    fun clear() {
        anchorSlot.clear()
        lastKnownSlot.clear()
    }
}
