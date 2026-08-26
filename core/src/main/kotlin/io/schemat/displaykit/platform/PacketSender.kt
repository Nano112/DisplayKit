package io.schemat.displaykit.platform

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.VirtualEntity
import java.util.UUID

interface PacketSender {
    /**
     * Spawn [entity] with its complete initial metadata.
     *
     * Callers must not immediately follow this with [updateMetadata]: packet
     * backends make the add packet and first metadata packet one lifecycle
     * operation so the client never observes a half-configured display.
     */
    fun spawnEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>)
    fun updateMetadata(entity: VirtualEntity, viewerUUIDs: Collection<UUID>)
    fun teleportEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>)
    fun destroyEntities(entityIds: Collection<Int>, viewerUUIDs: Collection<UUID>)

    /**
     * Update metadata for multiple entities in a single atomic bundle.
     * All updates are sent together so clients receive and process them simultaneously.
     */
    fun updateMetadataBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>)

    /**
     * Batch update only transform + interpolation fields for animation.
     * Much lighter than full metadata — skips blockState, brightness, viewRange, etc.
     */
    fun updateTransformBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>)

    /**
     * Spawn an invisible interaction entity to serve as a carrier/vehicle for display entity passengers.
     */
    fun spawnCarrierEntity(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>)

    /**
     * Teleport a carrier entity by raw ID + position (absolute, resets interpolation).
     * Use for large jumps. For smooth animation, prefer [moveCarrier].
     */
    fun teleportCarrier(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>)

    /**
     * Move a carrier entity by a position delta. Uses ClientboundMoveEntityPacket which the client
     * accumulates smoothly without resetting interpolation — same mechanism as boats/minecarts.
     * All passengers follow automatically. Max delta ~8 blocks per axis per call.
     */
    fun moveCarrier(entityId: Int, deltaX: Double, deltaY: Double, deltaZ: Double, viewerUUIDs: Collection<UUID>)

    /**
     * Set the passenger list for a vehicle entity. Passengers follow the vehicle's position.
     */
    fun setPassengers(vehicleEntityId: Int, passengerEntityIds: IntArray, viewerUUIDs: Collection<UUID>)
}
