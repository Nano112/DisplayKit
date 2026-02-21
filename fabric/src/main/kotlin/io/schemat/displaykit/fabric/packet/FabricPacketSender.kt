package io.schemat.displaykit.fabric.packet

import io.schemat.displaykit.platform.PacketSender
import io.schemat.displaykit.render.*
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
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

    private fun resolveViewers(uuids: Collection<UUID>): List<ServerPlayer> {
        return uuids.mapNotNull { server.playerList.getPlayer(it) }
    }
}
