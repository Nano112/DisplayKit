package io.schemat.displaykit.fabric.input

import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.platform.TextInput
import io.schemat.displaykit.render.TextComponent
import io.schemat.displaykit.fabric.thread.ServerThreadDispatcher
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FabricTextInput : TextInput {

    private data class InputSession(
        val playerUUID: UUID,
        val callback: (String?) -> Unit
    )

    private val activeSessions = ConcurrentHashMap<UUID, InputSession>()

    override fun requestInput(player: PlayerRef, currentValue: String, callback: (String?) -> Unit) {
        val session = InputSession(player.uuid, callback)
        activeSessions[player.uuid] = session

        player.sendMessage(TextComponent.of("Type your input in chat (or 'cancel' to cancel):"))
        if (currentValue.isNotEmpty()) {
            player.sendMessage(TextComponent.of("Current value: $currentValue"))
        }
    }

    /**
     * Capture one active request from the network handler and complete it on
     * the Minecraft server thread. `handleChat` is injected at HEAD and first
     * runs on Netty, before vanilla reschedules the packet; callbacks commonly
     * mutate a StateScope and must never run on that first pass.
     */
    fun handleChatMessage(player: ServerPlayer, message: String): Boolean =
        handleChatMessage(player.uuid, message) { completion ->
            ServerThreadDispatcher.dispatch(player.level().server, completion)
        }

    internal fun handleChatMessage(
        playerUUID: UUID,
        message: String,
        dispatch: (Runnable) -> Unit,
    ): Boolean {
        val session = activeSessions.remove(playerUUID) ?: return false
        dispatch(Runnable {
            if (message.equals("cancel", ignoreCase = true)) {
                session.callback(null)
            } else {
                session.callback(message)
            }
        })
        return true
    }

    /** Cancel during already-server-thread lifecycle teardown. */
    fun cancelInput(playerUUID: UUID) {
        val session = activeSessions.remove(playerUUID)
        session?.callback(null)
    }

    /** Cancel every outstanding request during owner-thread server teardown. */
    fun cancelAll() {
        activeSessions.keys.toList().forEach(::cancelInput)
    }

    fun hasActiveSession(playerUUID: UUID): Boolean = activeSessions.containsKey(playerUUID)
}
