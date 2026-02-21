package io.schemat.displaykit.fabric.input

import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.platform.TextInput
import io.schemat.displaykit.render.TextComponent
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

    fun handleChatMessage(playerUUID: UUID, message: String): Boolean {
        val session = activeSessions.remove(playerUUID) ?: return false
        if (message.equals("cancel", ignoreCase = true)) {
            session.callback(null)
        } else {
            session.callback(message)
        }
        return true
    }

    fun cancelInput(playerUUID: UUID) {
        val session = activeSessions.remove(playerUUID)
        session?.callback(null)
    }

    fun hasActiveSession(playerUUID: UUID): Boolean = activeSessions.containsKey(playerUUID)
}
