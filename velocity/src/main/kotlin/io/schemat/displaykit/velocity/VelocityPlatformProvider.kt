package io.schemat.displaykit.velocity

import com.velocitypowered.api.proxy.ProxyServer
import io.schemat.displaykit.platform.PacketSender
import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.platform.Scheduler
import io.schemat.displaykit.platform.TextInput
import io.schemat.displaykit.velocity.input.PlayerPositionCache
import io.schemat.displaykit.velocity.player.VelocityPlayerRef
import java.util.UUID
import java.util.logging.Logger

class VelocityPlatformProvider(
    private val proxy: ProxyServer,
    private val positions: PlayerPositionCache,
    override val logger: Logger,
    override val scheduler: Scheduler,
    override val packetSender: PacketSender,
    override val textInput: TextInput,
) : PlatformProvider {

    override fun getPlayer(uuid: UUID): PlayerRef? =
        proxy.getPlayer(uuid).orElse(null)?.let { player ->
            VelocityPlayerRef(proxy, positions, player)
        }

    override fun getOnlinePlayers(): Collection<PlayerRef> =
        proxy.allPlayers.map { player -> VelocityPlayerRef(proxy, positions, player) }
}
