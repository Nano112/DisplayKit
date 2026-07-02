package io.schemat.displaykit.ui

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3f
import io.schemat.displaykit.platform.PacketSender
import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.render.VirtualEntity
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-geometry tests for [Hologram]: spec bounds and re-anchoring. Uses a
 * no-op packet sender; entities are never actually sent anywhere.
 */
class HologramSpecTest {

    private class NoopSender : PacketSender {
        override fun spawnEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {}
        override fun updateMetadata(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {}
        override fun teleportEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {}
        override fun destroyEntities(entityIds: Collection<Int>, viewerUUIDs: Collection<UUID>) {}
        override fun updateMetadataBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {}
        override fun updateTransformBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {}
        override fun spawnCarrierEntity(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) {}
        override fun teleportCarrier(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) {}
        override fun moveCarrier(entityId: Int, deltaX: Double, deltaY: Double, deltaZ: Double, viewerUUIDs: Collection<UUID>) {}
        override fun setPassengers(vehicleEntityId: Int, passengerEntityIds: IntArray, viewerUUIDs: Collection<UUID>) {}
    }

    private fun platform(): PlatformProvider {
        // Only packetSender is exercised by Hologram.
        return object : PlatformProvider {
            override val packetSender = NoopSender()
            override val scheduler get() = throw UnsupportedOperationException("not used by Hologram")
        }
    }

    @Test
    fun emptyHologramHasNoBounds() {
        assertNull(Hologram(platform()).localBounds())
    }

    @Test
    fun boundsSpanAllSpecs() {
        val holo = Hologram(platform())
            .cuboid(Vec3d.ZERO, Vec3f(9f, 5f, 9f))
            .marker(Vec3d(10.0, 1.0, 4.0), Hologram.SENSOR_MARKER, size = 0.25f)

        val (min, max) = holo.localBounds()!!
        assertEquals(Vec3d(0.0, 0.0, 0.0), min)
        assertEquals(10.25, max.x, 1e-9)
        assertEquals(5.0, max.y, 1e-9)
        assertEquals(9.0, max.z, 1e-9)
    }

    @Test
    fun moveToReanchorsWithoutChangingLocalGeometry() {
        val holo = Hologram(platform()).cuboid(Vec3d(1.0, 0.0, 1.0), Vec3f(2f, 2f, 2f))
        val before = holo.localBounds()!!
        holo.moveTo(Vec3d(100.0, 64.0, -20.0))
        assertEquals(before, holo.localBounds()!!)
        assertEquals(Vec3d(100.0, 64.0, -20.0), holo.origin)
    }
}
