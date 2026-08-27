package io.schemat.displaykit.velocity.packet

import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PacketSender
import io.schemat.displaykit.render.EntityType
import io.schemat.displaykit.render.VirtualEntity
import java.util.Optional
import java.util.UUID

/**
 * PacketSender implemented with PacketEvents on the proxy.
 *
 * Everything is sent silently so the module's own packets never re-enter the
 * consumer's PacketEvents listeners; a proxy plugin hosting DisplayKit almost
 * certainly has its own send listeners and feeding them synthetic entities
 * would be a trap.
 *
 * Spawn and first metadata travel inside one bundle, honouring the
 * PacketSender contract that spawnEntity is a single lifecycle operation:
 * the client applies bundled packets in one tick, so a display can never be
 * seen half-configured.
 */
class PacketEventsSender(
    private val proxy: ProxyServer,
    private val packetEvents: PacketEventsAPI<*>,
    private val encoder: DisplayMetadataEncoder,
    private val viewerFilter: (Player) -> Boolean = { true },
) : PacketSender {

    override fun spawnEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
        val users = resolveViewers(viewerUUIDs)
        if (users.isEmpty()) return

        val metadata = encoder.encode(entity)
        for (user in users) {
            user.sendPacketSilently(WrapperPlayServerBundle())
            user.sendPacketSilently(spawnWrapper(entity))
            user.sendPacketSilently(WrapperPlayServerEntityMetadata(entity.entityId, metadata))
            user.sendPacketSilently(WrapperPlayServerBundle())
        }
        entity.markClean()
    }

    override fun updateMetadata(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
        val users = resolveViewers(viewerUUIDs)
        if (users.isEmpty()) return

        val metadata = encoder.encode(entity)
        sendToAll(users, WrapperPlayServerEntityMetadata(entity.entityId, metadata))
        entity.markClean()
    }

    override fun teleportEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
        val users = resolveViewers(viewerUUIDs)
        if (users.isEmpty()) return

        sendToAll(
            users,
            WrapperPlayServerEntityTeleport(
                entity.entityId,
                toVector(entity.position), 0f, 0f, false
            )
        )
    }

    override fun destroyEntities(entityIds: Collection<Int>, viewerUUIDs: Collection<UUID>) {
        val users = resolveViewers(viewerUUIDs)
        if (users.isEmpty() || entityIds.isEmpty()) return

        sendToAll(users, WrapperPlayServerDestroyEntities(*entityIds.toIntArray()))
    }

    override fun updateMetadataBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {
        val users = resolveViewers(viewerUUIDs)
        if (users.isEmpty() || entities.isEmpty()) return

        val payload = entities.map { entity -> entity.entityId to encoder.encode(entity) }
        for (user in users) {
            user.sendPacketSilently(WrapperPlayServerBundle())
            for ((entityId, metadata) in payload) {
                user.sendPacketSilently(WrapperPlayServerEntityMetadata(entityId, metadata))
            }
            user.sendPacketSilently(WrapperPlayServerBundle())
        }
        entities.forEach(VirtualEntity::markClean)
    }

    override fun updateTransformBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {
        val users = resolveViewers(viewerUUIDs)
        if (users.isEmpty() || entities.isEmpty()) return

        val payload = entities.map { entity -> entity.entityId to encoder.encodeTransformOnly(entity) }
        for (user in users) {
            user.sendPacketSilently(WrapperPlayServerBundle())
            for ((entityId, metadata) in payload) {
                user.sendPacketSilently(WrapperPlayServerEntityMetadata(entityId, metadata))
            }
            user.sendPacketSilently(WrapperPlayServerBundle())
        }
    }

    override fun spawnCarrierEntity(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) {
        val users = resolveViewers(viewerUUIDs)
        if (users.isEmpty()) return

        sendToAll(
            users,
            WrapperPlayServerSpawnEntity(
                entityId, Optional.of(UUID.randomUUID()), EntityTypes.INTERACTION,
                toVector(position), 0f, 0f, 0f, 0, Optional.empty()
            )
        )
    }

    override fun teleportCarrier(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) {
        val users = resolveViewers(viewerUUIDs)
        if (users.isEmpty()) return

        sendToAll(users, WrapperPlayServerEntityTeleport(entityId, toVector(position), 0f, 0f, false))
    }

    override fun moveCarrier(
        entityId: Int,
        deltaX: Double,
        deltaY: Double,
        deltaZ: Double,
        viewerUUIDs: Collection<UUID>,
    ) {
        val users = resolveViewers(viewerUUIDs)
        if (users.isEmpty()) return

        sendToAll(users, WrapperPlayServerEntityRelativeMove(entityId, deltaX, deltaY, deltaZ, false))
    }

    override fun setPassengers(vehicleEntityId: Int, passengerEntityIds: IntArray, viewerUUIDs: Collection<UUID>) {
        val users = resolveViewers(viewerUUIDs)
        if (users.isEmpty()) return

        sendToAll(users, WrapperPlayServerSetPassengers(vehicleEntityId, passengerEntityIds))
    }

    private fun spawnWrapper(entity: VirtualEntity): WrapperPlayServerSpawnEntity {
        val entityType = when (entity.entityType) {
            EntityType.BLOCK_DISPLAY -> EntityTypes.BLOCK_DISPLAY
            EntityType.TEXT_DISPLAY -> EntityTypes.TEXT_DISPLAY
            EntityType.ITEM_DISPLAY -> EntityTypes.ITEM_DISPLAY
        }
        return WrapperPlayServerSpawnEntity(
            entity.entityId, Optional.of(UUID.randomUUID()), entityType,
            toVector(entity.position), 0f, 0f, 0f, 0, Optional.empty()
        )
    }

    private fun resolveViewers(viewerUUIDs: Collection<UUID>): List<User> =
        viewerUUIDs.mapNotNull { uuid ->
            proxy.getPlayer(uuid).orElse(null)
                ?.takeIf(viewerFilter)
                ?.let { player -> packetEvents.playerManager.getUser(player) }
        }

    private fun sendToAll(users: List<User>, wrapper: PacketWrapper<*>) {
        for (user in users) {
            user.sendPacketSilently(wrapper)
        }
    }

    private fun toVector(position: Vec3d): Vector3d = Vector3d(position.x, position.y, position.z)
}
