package io.schemat.displaykit.world

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PacketSender
import io.schemat.displaykit.render.TextComponent
import io.schemat.displaykit.render.VirtualEntity
import io.schemat.displaykit.render.VirtualTextDisplay
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorldEntityLayerTest {
    private class RecordingPackets : PacketSender {
        val spawned = mutableListOf<Pair<Int, Set<UUID>>>()
        val destroyed = mutableListOf<Pair<List<Int>, Set<UUID>>>()
        val metadata = mutableListOf<List<Int>>()

        override fun spawnEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
            spawned += entity.entityId to viewerUUIDs.toSet()
        }
        override fun destroyEntities(entityIds: Collection<Int>, viewerUUIDs: Collection<UUID>) {
            destroyed += entityIds.toList() to viewerUUIDs.toSet()
        }
        override fun updateMetadataBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {
            metadata += entities.map { it.entityId }
        }
        override fun updateMetadata(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) = Unit
        override fun teleportEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) = Unit
        override fun updateTransformBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) = Unit
        override fun spawnCarrierEntity(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) = Unit
        override fun teleportCarrier(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) = Unit
        override fun moveCarrier(entityId: Int, deltaX: Double, deltaY: Double, deltaZ: Double, viewerUUIDs: Collection<UUID>) = Unit
        override fun setPassengers(vehicleEntityId: Int, passengerEntityIds: IntArray, viewerUUIDs: Collection<UUID>) = Unit
    }

    @Test
    fun `keyed reconciliation retains entities and batches changed metadata`() {
        val packets = RecordingPackets()
        val viewer = UUID.randomUUID()
        val layer = WorldEntityLayer<String, String>(
            packets,
            create = { text -> VirtualTextDisplay().apply { this.text = TextComponent.of(text) } },
            update = { entity, _, next ->
                (entity as VirtualTextDisplay).text = TextComponent.of(next)
                WorldEntityUpdate.METADATA
            },
        )

        layer.reconcile(mapOf("a" to "one", "b" to "two"), listOf(viewer))
        val identities = layer.keys
        assertEquals(2, packets.spawned.size)

        layer.reconcile(mapOf("a" to "ONE", "b" to "two"))
        assertEquals(identities, layer.keys)
        assertEquals(1, packets.metadata.single().size)
        assertEquals(2, packets.spawned.size)
    }

    @Test
    fun `viewer and removal diffs do not respawn retained viewers`() {
        val packets = RecordingPackets()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val layer = WorldEntityLayer<String, String>(packets, { VirtualTextDisplay() })

        layer.reconcile(mapOf("a" to "one"), listOf(first))
        layer.setViewers(listOf(first, second))
        assertEquals(2, packets.spawned.size)
        layer.reconcile(emptyMap())
        assertEquals(setOf(first, second), packets.destroyed.last().second)

        layer.close()
        assertFailsWith<IllegalStateException> { layer.reconcile(emptyMap()) }
    }

    @Test
    fun `radius policy diffs viewers independently and reports churn`() {
        val packets = RecordingPackets()
        val near = UUID.randomUUID()
        val far = UUID.randomUUID()
        var latest = WorldEntityLayerMetrics()
        val layer = WorldEntityLayer<String, String>(
            packets,
            create = { VirtualTextDisplay() },
            viewerPolicy = WorldViewerPolicies.radius({ Vec3d.ZERO }, 10.0),
            onMetrics = { latest = it },
        )
        layer.reconcile(mapOf("label" to "ready"))
        layer.reconcileViewers(listOf(
            WorldViewer(near, Vec3d(4.0, 0.0, 0.0)),
            WorldViewer(far, Vec3d(40.0, 0.0, 0.0)),
        ))

        assertEquals(setOf(near), layer.viewerIds)
        assertEquals(1, latest.activeEntities)
        assertEquals(1, latest.activeViewers)
        assertEquals(1, latest.entityViewerSpawns)

        layer.reconcileViewers(listOf(WorldViewer(far, Vec3d(2.0, 0.0, 0.0))))
        assertEquals(setOf(far), layer.viewerIds)
        assertEquals(2, latest.entityViewerSpawns)
        assertTrue(latest.entityViewerDestroys >= 1)
    }

    @Test
    fun `metadata and transform updates may be requested together`() {
        val packets = RecordingPackets()
        val viewer = UUID.randomUUID()
        var transforms = 0
        val forwarding = object : PacketSender by packets {
            override fun updateTransformBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {
                transforms += entities.size
            }
        }
        val layer = WorldEntityLayer<String, String>(
            forwarding,
            create = { VirtualTextDisplay() },
            update = { _, _, _ -> WorldEntityUpdate(metadata = true, transform = true) },
        )
        layer.reconcile(mapOf("both" to "before"), setOf(viewer))
        layer.reconcile(mapOf("both" to "after"))

        assertEquals(1, packets.metadata.single().size)
        assertEquals(1, transforms)
    }
}
