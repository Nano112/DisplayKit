package io.schemat.displaykit.fabric.packet

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PacketSender
import io.schemat.displaykit.render.*
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.phys.Vec3
import java.util.UUID

class FabricPacketSender(
    private val server: MinecraftServer
) : PacketSender {

    override fun spawnEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
        val players = resolveViewers(viewerUUIDs)
        if (players.isEmpty()) return

        val mcEntityType = when (entity.entityType) {
            io.schemat.displaykit.render.EntityType.BLOCK_DISPLAY -> EntityType.BLOCK_DISPLAY
            io.schemat.displaykit.render.EntityType.TEXT_DISPLAY -> EntityType.TEXT_DISPLAY
            io.schemat.displaykit.render.EntityType.ITEM_DISPLAY -> EntityType.ITEM_DISPLAY
        }

        val pos = entity.position
        val spawnPacket = ClientboundAddEntityPacket(
            entity.entityId,
            UUID.randomUUID(),
            pos.x, pos.y, pos.z,
            0f, 0f,
            mcEntityType,
            0,
            Vec3.ZERO,
            0.0
        )

        val metadataEntries = when (entity) {
            is VirtualBlockDisplay -> MetadataEncoder.encodeBlockDisplay(entity)
            is VirtualTextDisplay -> MetadataEncoder.encodeTextDisplay(entity)
            is VirtualItemDisplay -> MetadataEncoder.encodeItemDisplay(entity)
            else -> return
        }
        val metadataPacket = ClientboundSetEntityDataPacket(entity.entityId, metadataEntries)

        for (player in players) {
            player.connection.send(spawnPacket)
            player.connection.send(metadataPacket)
        }

        entity.markClean()
    }

    override fun updateMetadata(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
        val players = resolveViewers(viewerUUIDs)
        if (players.isEmpty()) return

        val metadataEntries = when (entity) {
            is VirtualBlockDisplay -> MetadataEncoder.encodeBlockDisplay(entity)
            is VirtualTextDisplay -> MetadataEncoder.encodeTextDisplay(entity)
            is VirtualItemDisplay -> MetadataEncoder.encodeItemDisplay(entity)
            else -> return
        }
        val packet = ClientboundSetEntityDataPacket(entity.entityId, metadataEntries)

        for (player in players) {
            player.connection.send(packet)
        }

        entity.markClean()
    }

    override fun teleportEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
        val players = resolveViewers(viewerUUIDs)
        if (players.isEmpty()) return

        val pos = entity.position
        val positionMoveRotation = PositionMoveRotation(
            Vec3(pos.x, pos.y, pos.z),
            Vec3.ZERO,
            0f, 0f
        )
        val packet = ClientboundTeleportEntityPacket(
            entity.entityId,
            positionMoveRotation,
            emptySet(),
            false
        )

        for (player in players) {
            player.connection.send(packet)
        }
    }

    override fun destroyEntities(entityIds: Collection<Int>, viewerUUIDs: Collection<UUID>) {
        val players = resolveViewers(viewerUUIDs)
        if (players.isEmpty() || entityIds.isEmpty()) return

        val intList = IntArrayList(entityIds.size)
        for (id in entityIds) intList.add(id)
        val packet = ClientboundRemoveEntitiesPacket(intList)

        for (player in players) {
            player.connection.send(packet)
        }
    }

    override fun updateMetadataBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {
        val players = resolveViewers(viewerUUIDs)
        if (players.isEmpty() || entities.isEmpty()) return

        val packets = mutableListOf<Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>>()
        for (entity in entities) {
            val metadataEntries = when (entity) {
                is VirtualBlockDisplay -> MetadataEncoder.encodeBlockDisplay(entity)
                is VirtualTextDisplay -> MetadataEncoder.encodeTextDisplay(entity)
                is VirtualItemDisplay -> MetadataEncoder.encodeItemDisplay(entity)
                else -> continue
            }
            packets.add(ClientboundSetEntityDataPacket(entity.entityId, metadataEntries))
            entity.markClean()
        }

        sendBundled(packets, players)
    }

    override fun updateTransformBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {
        val players = resolveViewers(viewerUUIDs)
        if (players.isEmpty() || entities.isEmpty()) return

        val packets = mutableListOf<Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>>()
        for (entity in entities) {
            val metadataEntries = MetadataEncoder.encodeTransformOnly(entity)
            packets.add(ClientboundSetEntityDataPacket(entity.entityId, metadataEntries))
            entity.markClean()
        }

        sendBundled(packets, players)
    }

    override fun spawnCarrierEntity(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) {
        val players = resolveViewers(viewerUUIDs)
        if (players.isEmpty()) return

        val packet = ClientboundAddEntityPacket(
            entityId,
            UUID.randomUUID(),
            position.x, position.y, position.z,
            0f, 0f,
            EntityType.INTERACTION,
            0,
            Vec3.ZERO,
            0.0
        )

        for (player in players) {
            player.connection.send(packet)
        }
    }

    override fun teleportCarrier(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) {
        val players = resolveViewers(viewerUUIDs)
        if (players.isEmpty()) return

        val positionMoveRotation = PositionMoveRotation(
            Vec3(position.x, position.y, position.z),
            Vec3.ZERO,
            0f, 0f
        )
        val packet = ClientboundTeleportEntityPacket(
            entityId,
            positionMoveRotation,
            emptySet(),
            false
        )

        for (player in players) {
            player.connection.send(packet)
        }
    }

    override fun moveCarrier(entityId: Int, deltaX: Double, deltaY: Double, deltaZ: Double, viewerUUIDs: Collection<UUID>) {
        val players = resolveViewers(viewerUUIDs)
        if (players.isEmpty()) return

        // Encode as shorts: 1 unit = 1/4096 of a block
        val dx = (deltaX * 4096).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        val dy = (deltaY * 4096).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        val dz = (deltaZ * 4096).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        val packet = ClientboundMoveEntityPacket.Pos(entityId, dx, dy, dz, false)

        for (player in players) {
            player.connection.send(packet)
        }
    }

    override fun setPassengers(vehicleEntityId: Int, passengerEntityIds: IntArray, viewerUUIDs: Collection<UUID>) {
        val players = resolveViewers(viewerUUIDs)
        if (players.isEmpty()) return

        // Construct via STREAM_CODEC since the only public constructor requires a real Entity
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(vehicleEntityId)
        buf.writeVarInt(passengerEntityIds.size)
        for (id in passengerEntityIds) {
            buf.writeVarInt(id)
        }
        val packet = ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf)
        buf.release()

        for (player in players) {
            player.connection.send(packet)
        }
    }

    private fun sendBundled(
        packets: List<Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>>,
        players: List<ServerPlayer>
    ) {
        // Single bundle so all entities update atomically in the same client tick
        val bundle = ClientboundBundlePacket(packets)
        for (player in players) {
            player.connection.send(bundle)
        }
    }

    private fun resolveViewers(uuids: Collection<UUID>): List<ServerPlayer> {
        return uuids.mapNotNull { server.playerList.getPlayer(it) }
    }
}
