package io.schemat.displaykit.velocity

import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import java.util.UUID

/**
 * Decides per player how much of DisplayKit their client can render.
 *
 * The proxy speaks each client's own protocol version, so this gate is the
 * consumer's contract for fallback: the module renders one metadata layout
 * (the modern one) and refuses to address older clients rather than sending
 * them packets they would misparse.
 *
 * Floors, from the toolkit's actual feature requirements rather than the
 * display-entity introduction version:
 *  - ENTITIES: pack-free surfaces use sprite object contents, a 1.21.9
 *    client feature.
 *  - FULL: composited pack surfaces target the 1.21.11-era metadata and
 *    pack format; the enum in the shipped PacketEvents has no 1.21.11
 *    constant, so the next version it does carry is the floor.
 */
class VersionGate(
    private val proxy: ProxyServer,
    private val packetEvents: PacketEventsAPI<*>,
) {

    enum class SupportLevel { NONE, ENTITIES, FULL }

    fun supportLevel(playerId: UUID): SupportLevel {
        val player = proxy.getPlayer(playerId).orElse(null) ?: return SupportLevel.NONE
        return supportLevel(player)
    }

    fun supportLevel(player: Player): SupportLevel {
        val version = try {
            packetEvents.playerManager.getClientVersion(player)
        } catch (_: Exception) {
            return SupportLevel.NONE
        } ?: return SupportLevel.NONE

        return when {
            version.isNewerThanOrEquals(ClientVersion.V_26_1) -> SupportLevel.FULL
            version.isNewerThanOrEquals(ClientVersion.V_1_21_9) -> SupportLevel.ENTITIES
            else -> SupportLevel.NONE
        }
    }

    fun isSupported(player: Player): Boolean = supportLevel(player) != SupportLevel.NONE

    fun isSupported(playerId: UUID): Boolean = supportLevel(playerId) != SupportLevel.NONE
}
