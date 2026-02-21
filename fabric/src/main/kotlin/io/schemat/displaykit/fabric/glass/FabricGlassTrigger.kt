package io.schemat.displaykit.fabric.glass

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.GlassTrigger
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fabric implementation of the glass trigger system.
 *
 * Uses packet-based virtual armor stands (per-player, no server entity)
 * with Glowing flag to trigger the entity_outline post-processing pipeline.
 */
object FabricGlassTrigger {
    private val LOGGER = LoggerFactory.getLogger("DisplayKit-GlassTrigger")

    // Entity flags (metadata index 0)
    private const val FLAG_INVISIBLE: Byte = 0x20
    private const val FLAG_GLOWING: Byte = 0x40

    // ArmorStand flags (metadata index 15 for armor stands)
    private const val AS_FLAG_SMALL: Byte = 0x01
    private const val AS_FLAG_NO_BASEPLATE: Byte = 0x08

    // Virtual entity ID counter (use negative IDs to avoid collision with real entities)
    private val nextEntityId = AtomicInteger(-1000)

    // Track virtual entity IDs per player
    private val virtualEntities = ConcurrentHashMap<Int, VirtualTrigger>()

    private var server: MinecraftServer? = null

    private data class VirtualTrigger(
        val entityId: Int,
        val playerId: UUID
    )

    fun initialize(minecraftServer: MinecraftServer) {
        server = minecraftServer

        GlassTrigger.onSpawnTrigger = ::spawnTriggerEntity
        GlassTrigger.onDespawnTrigger = ::despawnTriggerEntity
        GlassTrigger.onUpdatePosition = ::updateTriggerPosition

        LOGGER.info("Glass trigger system initialized (packet-based)")
    }

    fun shutdown() {
        GlassTrigger.clear()

        // Send destroy packets for any remaining virtual entities
        for ((_, trigger) in virtualEntities) {
            getPlayer(trigger.playerId)?.let { player ->
                val intList = IntArrayList(1)
                intList.add(trigger.entityId)
                player.connection.send(ClientboundRemoveEntitiesPacket(intList))
            }
        }
        virtualEntities.clear()

        GlassTrigger.onSpawnTrigger = null
        GlassTrigger.onDespawnTrigger = null
        GlassTrigger.onUpdatePosition = null

        server = null
    }

    private fun spawnTriggerEntity(playerId: UUID, position: Vec3d): Int {
        val player = getPlayer(playerId) ?: return -1

        val entityId = nextEntityId.decrementAndGet()

        // Spawn packet: create an armor stand at position
        val spawnPacket = ClientboundAddEntityPacket(
            entityId,
            UUID.randomUUID(),
            position.x, position.y, position.z,
            0f, 0f,
            EntityType.ARMOR_STAND,
            0,
            Vec3.ZERO,
            0.0
        )

        // Metadata: invisible + glowing + small + no baseplate
        val flags = (FLAG_INVISIBLE.toInt() or FLAG_GLOWING.toInt()).toByte()
        val asFlags = (AS_FLAG_SMALL.toInt() or AS_FLAG_NO_BASEPLATE.toInt()).toByte()

        val metadata = listOf(
            // Index 0: Entity flags (invisible + glowing)
            SynchedEntityData.DataValue.create(
                EntityDataSerializers.BYTE.createAccessor(0), flags
            ),
            // Index 15: ArmorStand flags (small + no baseplate)
            SynchedEntityData.DataValue.create(
                EntityDataSerializers.BYTE.createAccessor(15), asFlags
            )
        )
        val metadataPacket = ClientboundSetEntityDataPacket(entityId, metadata)

        // Send only to the target player
        player.connection.send(spawnPacket)
        player.connection.send(metadataPacket)

        virtualEntities[entityId] = VirtualTrigger(entityId, playerId)

        LOGGER.debug("Spawned virtual glass trigger {} for player {}", entityId, playerId)
        return entityId
    }

    private fun despawnTriggerEntity(playerId: UUID, entityId: Int) {
        val trigger = virtualEntities.remove(entityId) ?: return
        val player = getPlayer(trigger.playerId) ?: return

        val intList = IntArrayList(1)
        intList.add(entityId)
        player.connection.send(ClientboundRemoveEntitiesPacket(intList))

        LOGGER.debug("Despawned virtual glass trigger {} for player {}", entityId, playerId)
    }

    private fun updateTriggerPosition(playerId: UUID, entityId: Int, position: Vec3d) {
        val player = getPlayer(playerId) ?: return

        val packet = ClientboundTeleportEntityPacket(
            entityId,
            PositionMoveRotation(
                Vec3(position.x, position.y, position.z),
                Vec3.ZERO,
                0f, 0f
            ),
            emptySet(),
            false
        )
        player.connection.send(packet)
    }

    fun getPlayer(playerId: UUID): ServerPlayer? {
        return server?.playerList?.getPlayer(playerId)
    }
}
