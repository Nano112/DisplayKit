package io.schemat.displaykit.velocity.player

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.render.TextComponent
import io.schemat.displaykit.velocity.input.PlayerPositionCache
import io.schemat.displaykit.velocity.text.AdventureText
import java.util.UUID

/**
 * PlayerRef over a Velocity player, with eye and look coming from the sniffed
 * position cache since the proxy has no world of its own.
 *
 * Until the first movement packet arrives the cache is empty and both vectors
 * fall back to zero; a surface opened that early rejects its anchor and the
 * consumer retries, which is the same failure shape Fabric has for an offline
 * owner.
 */
class VelocityPlayerRef(
    private val proxy: ProxyServer,
    private val positions: PlayerPositionCache,
    override val uuid: UUID,
    override val name: String,
) : PlayerRef {

    constructor(proxy: ProxyServer, positions: PlayerPositionCache, player: Player) :
        this(proxy, positions, player.uniqueId, player.username)

    override fun eyePosition(): Vec3d = positions.eyePosition(uuid) ?: Vec3d.ZERO

    override fun lookDirection(): Vec3d = positions.lookDirection(uuid) ?: FALLBACK_LOOK

    override fun isOnline(): Boolean = proxy.getPlayer(uuid).isPresent

    override fun sendMessage(message: TextComponent) {
        proxy.getPlayer(uuid).ifPresent { player ->
            player.sendMessage(AdventureText.toAdventure(message))
        }
    }

    private companion object {
        val FALLBACK_LOOK = Vec3d(0.0, 0.0, 1.0)
    }
}
