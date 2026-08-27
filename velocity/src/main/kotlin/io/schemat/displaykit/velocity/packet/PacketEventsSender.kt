package io.schemat.displaykit.velocity.packet

import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
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
 * No bundle delimiters, deliberately. On a proxy every write is its own
 * event-loop task, so the forwarded backend's own 1.19.4+ bundle delimiters
 * could interleave between ours and invert the client's bundle phase, which
 * corrupts batching for everything after it. Sequential sends risk at most
 * one frame of a half-configured display, and the metadata follows the spawn
 * immediately on the same channel.
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

        val byVersion = HashMap<ClientVersion, List<EntityData<*>>>()
        for (user in users) {
            val metadata = byVersion.getOrPut(versionOf(user)) { encoder.encode(entity, user) }
            user.sendPacketSilently(spawnWrapper(entity))
            user.sendPacketSilently(WrapperPlayServerEntityMetadata(entity.entityId, metadata))
        }
        entity.markClean()
    }

    override fun updateMetadata(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
        val users = resolveViewers(viewerUUIDs)
        if (users.isEmpty()) return

        val byVersion = HashMap<ClientVersion, List<EntityData<*>>>()
        for (user in users) {
            val metadata = byVersion.getOrPut(versionOf(user)) { encoder.encode(entity, user) }
            user.sendPacketSilently(WrapperPlayServerEntityMetadata(entity.entityId, metadata))
        }
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

        // Metadata only varies by protocol version, not by viewer, so a
        // shared surface encodes once per distinct version rather than once
        // per player
        val byVersion = HashMap<ClientVersion, List<Pair<Int, List<EntityData<*>>>>>()
        for (user in users) {
            val payload = byVersion.getOrPut(versionOf(user)) {
                entities.map { entity -> entity.entityId to encoder.encode(entity, user) }
            }
            for ((entityId, metadata) in payload) {
                user.sendPacketSilently(WrapperPlayServerEntityMetadata(entityId, metadata))
            }
        }
        entities.forEach(VirtualEntity::markClean)
    }

    override fun updateTransformBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {
        val users = resolveViewers(viewerUUIDs)
        if (users.isEmpty() || entities.isEmpty()) return

        val payload = entities.map { entity -> entity.entityId to encoder.encodeTransformOnly(entity) }
        for (user in users) {
            for ((entityId, metadata) in payload) {
                user.sendPacketSilently(WrapperPlayServerEntityMetadata(entityId, metadata))
            }
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

    // A user whose version is not known yet is treated as current: the
    // encoder falls back the same way, so both agree on one cache slot
    private fun versionOf(user: User): ClientVersion =
        user.clientVersion ?: ClientVersion.getLatest()

    private fun sendToAll(users: List<User>, wrapper: PacketWrapper<*>) {
        for (user in users) {
            user.sendPacketSilently(wrapper)
        }
    }

    private fun toVector(position: Vec3d): Vector3d = Vector3d(position.x, position.y, position.z)
}
