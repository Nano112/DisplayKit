package io.schemat.displaykit.fabric.input

import io.schemat.displaykit.surface.ScrollWrap
import io.schemat.displaykit.surface.SurfaceFocus
import io.schemat.displaykit.ui.InteractionRouter
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Synthesises scroll events from hotbar selection changes.
 *
 * Scoped deliberately: capture only happens while the pointer is inside
 * something scrollable, so the hotbar behaves normally everywhere else. The
 * player's slot is remembered on first capture and restored when it ends.
 *
 * Threading: [onSlotChange] runs on the netty thread (see the mixin
 * `io.schemat.displaykit.fabric.mixin.SetCarriedItemMixin` for why
 * cancelling pre-empts the usual reschedule onto the server thread). Reading
 * [SurfaceFocus.isScrollArmed] there is safe -- its backing fields are
 * volatile precisely for this. But scrolling a node, repainting and sending
 * entity metadata are all game-state mutations, so that work is hopped onto
 * the server thread via [ServerPlayer.getServer]`.execute`. The cancel
 * decision itself can't wait for that hop to finish -- Mixin needs an
 * immediate true/false -- so it is made purely from [SurfaceFocus.isScrollArmed]:
 * if the pointer is over a scrollable, the wheel belongs to the surface, and
 * we cancel and snap the client back regardless of whether a node ends up
 * consuming the notch.
 *
 * `ServerPlayer.server` is private (compile-checked, not just undocumented);
 * `player.level().server` is the accessor already used elsewhere in this
 * codebase (see `HotbarMenu.kt`), so that's what's used here too.
 */
object HotbarScrollCapture {

    private val heldSlot = ConcurrentHashMap<UUID, Int>()

    /**
     * Returns true if the packet should be CANCELLED — i.e. this was a scroll
     * on a surface rather than a genuine hotbar change.
     */
    fun onSlotChange(player: ServerPlayer, newSlot: Int): Boolean {
        val id = player.uuid
        if (!SurfaceFocus.isScrollArmed(id)) {
            // Not over a scrollable: let the hotbar work, and forget any
            // remembered slot so the next capture starts fresh.
            heldSlot.remove(id)
            return false
        }
        val anchor = heldSlot.getOrPut(id) { player.inventory.selectedSlot }
        val delta = ScrollWrap.delta(anchor, newSlot)
        if (delta == 0) return false

        // Resolve on the server thread: handleScroll walks the surface tree,
        // may repaint, and sends entity metadata packets, none of which is
        // safe from the netty thread this handler runs on. The corrective
        // held-slot packet is sent from inside the same block, after the
        // scroll resolves, for consistency (connection.send itself is safe
        // to call from any thread and just queues the packet either way).
        player.level().server.execute {
            InteractionRouter.getSurfaces(id).any { it.handleScroll(delta) }
            player.connection.send(ClientboundSetHeldSlotPacket(anchor))
        }
        return true
    }

    /** Restore the remembered slot and stop capturing for this player. */
    fun release(player: ServerPlayer) {
        val anchor = heldSlot.remove(player.uuid) ?: return
        player.connection.send(ClientboundSetHeldSlotPacket(anchor))
    }

    fun forget(playerId: UUID) {
        heldSlot.remove(playerId)
    }
}
