package io.schemat.displaykit.fabric

import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.platform.*
import net.minecraft.server.MinecraftServer
import java.util.UUID
import java.util.logging.Logger

class FabricPlatformProvider(
    private val server: MinecraftServer,
    override val logger: Logger,
    override val scheduler: Scheduler,
    override val packetSender: PacketSender,
    override val textInput: TextInput
) : PlatformProvider {

    override fun getPlayer(uuid: UUID): PlayerRef? {
        val player = server.playerList.getPlayer(uuid) ?: return null
        return FabricPlayerRef(player)
    }

    override fun getOnlinePlayers(): Collection<PlayerRef> {
        return server.playerList.players.map { FabricPlayerRef(it) }
    }
}
