package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PacketSender
import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.platform.Scheduler
import io.schemat.displaykit.platform.TextInput
import io.schemat.displaykit.render.TextComponent
import io.schemat.displaykit.render.VirtualEntity
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode
import io.schemat.displaykit.state.StateScope
import io.schemat.displaykit.ui.InteractionRouter
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldSurfaceSessionTest {

    private class RecordingSender : PacketSender {
        var spawns = 0
        var metadata = 0
        var teleports = 0
        val destroyed = mutableListOf<Int>()

        override fun spawnEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) { spawns++ }
        override fun updateMetadata(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) { metadata++ }
        override fun destroyEntities(entityIds: Collection<Int>, viewerUUIDs: Collection<UUID>) {
            destroyed += entityIds
        }
        override fun teleportEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) { teleports++ }
        override fun updateMetadataBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {}
        override fun updateTransformBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {}
        override fun spawnCarrierEntity(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) {}
        override fun teleportCarrier(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) {}
        override fun moveCarrier(entityId: Int, deltaX: Double, deltaY: Double, deltaZ: Double, viewerUUIDs: Collection<UUID>) {}
        override fun setPassengers(vehicleEntityId: Int, passengerEntityIds: IntArray, viewerUUIDs: Collection<UUID>) {}
    }

    private class FakePlayer(override val uuid: UUID = UUID.randomUUID()) : PlayerRef {
        override val name = "session-test"
        var eye = Vec3d(0.0, 70.0, -2.0)
        var look = Vec3d(0.0, 0.0, 1.0)
        var online = true
        override fun eyePosition() = eye
        override fun lookDirection() = look
        override fun isOnline() = online
        override fun sendMessage(message: TextComponent) {}
    }

    private fun platform(sender: PacketSender): PlatformProvider = object : PlatformProvider {
        override val logger: Logger = Logger.getLogger("session-test")
        override val packetSender = sender
        override val scheduler: Scheduler get() = error("unused")
        override val textInput: TextInput get() = error("unused")
        override fun getPlayer(uuid: UUID): PlayerRef? = null
        override fun getOnlinePlayers(): Collection<PlayerRef> = emptyList()
    }

    private fun surface(): Surface = Surface(
        widthPx = 100,
        requestedHeightPx = 100,
        position = Vec3d.ZERO,
        targetWidthBlocks = 1f
    ).also { surface ->
        surface.renderMode = RenderMode.ENTITIES
        surface.layout { root ->
            root.addChild(WidgetNode("label", PxSize(100, 10)) { painter, rect ->
                painter.label("value", rect.x, rect.y)
            })
        }
    }

    @Test
    fun openCentersPaintsSpawnsAndRegistersThenCloseCleansEverything() {
        val sender = RecordingSender()
        val player = FakePlayer()
        val surface = surface()
        val center = Vec3d(0.5, 70.5, 0.0)
        val session = WorldSurfaceSession(
            platform(sender), player, surface,
            SurfaceAnchor.fixed(center, 0f),
            SurfaceLifecyclePolicy(maxDistance = 10.0, timeoutTicks = 100)
        )

        assertTrue(session.open())
        assertTrue(session.isOpen)
        assertTrue(sender.spawns > 0)
        assertTrue(session.host in InteractionRouter.getSurfaces(player.uuid))

        val expected = SurfacePlacement.centeredOrigin(
            center,
            0f,
            (surface.widthPx * surface.pixelScale * io.schemat.displaykit.render.TextMetrics.PIXEL_SIZE).toDouble(),
            (surface.heightPx * surface.pixelScale * io.schemat.displaykit.render.TextMetrics.PIXEL_SIZE).toDouble()
        )
        assertEquals(expected, surface.position)

        val liveIds = session.host.entities().map { it.entityId }.toSet()
        session.close()

        assertFalse(session.isOpen)
        assertEquals(SurfaceCloseReason.MANUAL, session.closeReason)
        assertTrue(session.host !in InteractionRouter.getSurfaces(player.uuid))
        assertEquals(liveIds, sender.destroyed.toSet())
    }

    @Test
    fun dynamicAnchorMovesExistingEntitiesWithoutRespawningThem() {
        val sender = RecordingSender()
        val player = FakePlayer()
        var pose = SurfacePose(Vec3d(0.5, 70.5, 0.0), 0f)
        val session = WorldSurfaceSession(
            platform(sender), player, surface(),
            SurfaceAnchor.dynamic { pose },
            SurfaceLifecyclePolicy(maxDistance = 20.0, timeoutTicks = 100)
        )
        session.open()
        val ids = session.host.entities().map { it.entityId }
        val spawnCount = sender.spawns

        pose = SurfacePose(Vec3d(1.0, 70.5, 0.0), 0f)
        session.host.tick()

        assertEquals(spawnCount, sender.spawns, "moving an anchor must reconcile, not respawn")
        assertEquals(ids, session.host.entities().map { it.entityId })
        assertTrue(sender.teleports > 0, "moving an anchor must move its live entities")
        session.close()
    }

    @Test
    fun boundStateBatchRepaintsOnceAndPreservesEntityIdentity() {
        val sender = RecordingSender()
        val player = FakePlayer()
        val stateScope = StateScope()
        val label = stateScope.mutable("one")
        val surface = Surface(100, 100, Vec3d.ZERO, 1f).also { candidate ->
            candidate.renderMode = RenderMode.ENTITIES
            candidate.layout { root ->
                root.addChild(WidgetNode("label", PxSize(100, 10)) { painter, rect ->
                    painter.label(label.value, rect.x, rect.y)
                })
            }
        }
        val session = WorldSurfaceSession(
            platform(sender), player, surface,
            SurfaceAnchor.fixed(Vec3d(0.5, 70.5, 0.0), 0f),
            SurfaceLifecyclePolicy(maxDistance = 10.0, timeoutTicks = 100)
        )
        session.bind(stateScope)
        session.open()
        val ids = session.host.entities().map { it.entityId }
        val spawns = sender.spawns
        val metadata = sender.metadata

        stateScope.batch {
            label.set("two")
            label.set("three")
        }

        assertEquals(ids, session.host.entities().map { it.entityId })
        assertEquals(spawns, sender.spawns, "a state repaint must reconcile existing entities")
        assertEquals(metadata + 1, sender.metadata, "a batch should publish one final text update")

        session.close()
        val afterClose = sender.metadata
        label.set("ignored by the closed surface")
        assertEquals(afterClose, sender.metadata, "session close must detach state bindings")
        stateScope.close()
    }

    @Test
    fun lifecycleClosesForDistanceTimeoutAndOfflineOwner() {
        fun run(reason: SurfaceCloseReason, policy: SurfaceLifecyclePolicy, mutate: (FakePlayer) -> Unit) {
            val player = FakePlayer()
            val session = WorldSurfaceSession(
                platform(RecordingSender()), player, surface(),
                SurfaceAnchor.fixed(Vec3d(0.5, 70.5, 0.0), 0f),
                policy
            )
            session.open()
            mutate(player)
            session.host.tick()
            assertEquals(reason, session.closeReason)
            assertFalse(session.isOpen)
            assertTrue(session.host !in InteractionRouter.getSurfaces(player.uuid))
        }

        run(
            SurfaceCloseReason.OUT_OF_RANGE,
            SurfaceLifecyclePolicy(maxDistance = 3.0, timeoutTicks = 100)
        ) { it.eye = Vec3d(20.0, 70.0, -2.0) }

        run(
            SurfaceCloseReason.TIMEOUT,
            SurfaceLifecyclePolicy(maxDistance = 10.0, timeoutTicks = 1)
        ) {}

        run(
            SurfaceCloseReason.OWNER_OFFLINE,
            SurfaceLifecyclePolicy(maxDistance = 10.0, timeoutTicks = 100)
        ) { it.online = false }
    }

    @Test
    fun verticalFaceUsesOutwardNormalForOutsetAndReadableYaw() {
        val player = FakePlayer()
        val pose = SurfaceAnchor.verticalFace(
            center = Vec3d(4.0, 70.0, 8.0),
            outward = Vec3d(0.0, 0.0, -1.0),
            outset = 0.125
        ).resolve(player)!!

        assertEquals(Vec3d(4.0, 70.0, 7.875), pose.center)
        assertEquals(Surface.yawFacing(Vec3d(0.0, 0.0, 1.0)), pose.yawDegrees)
    }
}
