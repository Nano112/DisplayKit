package io.schemat.displaykit.fabric.input

import io.schemat.displaykit.surface.SurfaceFocus
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Routes a player's chat into their terminal instead of world chat while
 * [SurfaceFocus.isTextArmed] is true for them.
 *
 * Deliberately its own object rather than logic bolted onto
 * [FabricTextInput]: that class serves an unrelated request/response input
 * flow (`requestInput`/`handleChatMessage`) with no notion of terminals, and
 * terminal ownership (which player has which terminal open) lives in the
 * showcase module, which `fabric` does not and should not depend on. [router]
 * is how the showcase module (or whatever else owns terminals) plugs itself
 * in without that dependency running backwards.
 *
 * [ChatPacketMixin] calls [onChatMessage] ALONGSIDE its existing
 * `FabricTextInput.handleChatMessage` check, in the same injection — this
 * class adds no second mixin on `handleChat`.
 *
 * Threading: like [HotbarScrollCapture], [onChatMessage] runs on the netty
 * thread (`ChatPacketMixin` cancels at `HEAD`, pre-empting vanilla's own
 * reschedule onto the server thread — see that mixin's KDoc). Reading
 * [SurfaceFocus.isTextArmed] there is safe, its backing fields are volatile
 * for exactly this. The immediate true/false Mixin needs is made purely from
 * that read; the actual routing — appending to the terminal, repainting,
 * sending packets — is real game-state mutation, so [router] is always
 * invoked from inside `player.level().server.execute { }`, never directly
 * from this thread. `player.server` is private; `player.level().server` is
 * the accessor already used for this elsewhere (see `HotbarMenu.kt`).
 */
object TerminalChatCapture {

    /**
     * Set by whichever module owns terminal windows. Runs on the SERVER
     * thread (see class KDoc) with the raw, unsigned chat text.
     */
    var router: ((UUID, String) -> Unit)? = null

    /**
     * Returns true if the packet should be CANCELLED: the player's pointer
     * was over a [io.schemat.displaykit.surface.SurfaceNodeMarker.TextCapturing]
     * surface, so this message belongs to their terminal, not world chat.
     */
    fun onChatMessage(player: ServerPlayer, message: String): Boolean {
        if (!SurfaceFocus.isTextArmed(player.uuid)) return false
        val r = router ?: return false
        val uuid = player.uuid
        player.level().server.execute { r(uuid, message) }
        return true
    }
}
