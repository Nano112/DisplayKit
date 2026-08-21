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
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Exercises [SurfaceHost.tick] end to end against a recording platform
 * double, rather than testing [Surface]/[SurfaceFocus] in isolation the way
 * [SurfaceHostTickTest] does.
 *
 * This class exists because two real defects in `tick()`'s pointer-lost path
 * (a grab going dead the instant the ray left the surface, and a
 * `PointerExit` dispatched with no target so it never actually fired) both
 * survived a green 215/215 suite that never drove a real [SurfaceHost]. Pure
 * `Surface`/`SurfaceFocus` unit tests cannot catch either bug -- they only
 * show up when something actually calls `tick()` twice in a row with the ray
 * moving on and off the panel.
 */
class SurfaceHostTest {

    /**
     * Counts packets instead of sending them, so a test can assert on *how
     * many* packets a tick produced -- the whole point of "repaint only on
     * hover change" (~140 metadata packets/sec/viewer if that rule ever
     * lapses).
     */
    private class RecordingPacketSender : PacketSender {
        var spawnCount = 0
            private set
        var updateMetadataCount = 0
            private set
        val destroyedIds = mutableListOf<Int>()

        override fun spawnEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
            spawnCount++
        }

        override fun updateMetadata(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
            updateMetadataCount++
        }

        override fun destroyEntities(entityIds: Collection<Int>, viewerUUIDs: Collection<UUID>) {
            destroyedIds += entityIds
        }

        // Unused by SurfaceHost -- not what this double is for.
        override fun teleportEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {}
        override fun updateMetadataBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {}
        override fun updateTransformBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {}
        override fun spawnCarrierEntity(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) {}
        override fun teleportCarrier(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) {}
        override fun moveCarrier(entityId: Int, deltaX: Double, deltaY: Double, deltaZ: Double, viewerUUIDs: Collection<UUID>) {}
        override fun setPassengers(vehicleEntityId: Int, passengerEntityIds: IntArray, viewerUUIDs: Collection<UUID>) {}
    }

    /** Only [PlatformProvider.packetSender] is exercised by [SurfaceHost]. */
    private fun platform(sender: PacketSender): PlatformProvider = object : PlatformProvider {
        override val logger: Logger = Logger.getLogger("test")
        override val packetSender: PacketSender = sender
        override val scheduler: Scheduler
            get() = throw NotImplementedError("not used by SurfaceHost")
        override val textInput: TextInput
            get() = throw NotImplementedError("not used by SurfaceHost")

        override fun getPlayer(uuid: UUID): PlayerRef? = null
        override fun getOnlinePlayers(): Collection<PlayerRef> = emptyList()
    }

    /** A player whose eye/look can be re-aimed on or off the surface between ticks. */
    private class FakePlayer(override val uuid: UUID = UUID.randomUUID()) : PlayerRef {
        override val name: String = "fake"
        var eye: Vec3d = ON_SURFACE_EYE
        var look: Vec3d = STRAIGHT_AHEAD

        override fun eyePosition(): Vec3d = eye
        override fun lookDirection(): Vec3d = look
        override fun isOnline(): Boolean = true
        override fun sendMessage(message: TextComponent) {}
    }

    companion object {
        // Same rig as SurfacePickingTest: 100x100px at 1 block wide, origin at
        // world (0,70,0), so a ray from here hits canvas pixel (0,0).
        private val ON_SURFACE_EYE = Vec3d(0.0, 70.0, -2.0)
        private val STRAIGHT_AHEAD = Vec3d(0.0, 0.0, 1.0)

        // x=5 is outside the 1-block-wide panel's [0,1) world-x range, so
        // straight-ahead from here misses the plane's rectangle entirely --
        // see SurfacePickingTest.aRayMissingTheRectangleReturnsNull.
        private val OFF_SURFACE_EYE = Vec3d(5.0, 70.0, -2.0)
    }

    private fun surface() = Surface(100, 100, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 1f)

    @Test
    fun grabKeepsReceivingMovementAfterTheRayLeavesTheSurface() {
        val s = surface()
        val moves = mutableListOf<Pair<Int, Int>>()
        s.layout { root ->
            val thumb = WidgetNode("thumb", PxSize(10, 10))
            thumb.onGrabMove = { x, y -> moves += x to y }
            root.addChild(thumb)
        }
        val player = FakePlayer()
        val host = SurfaceHost(platform(RecordingPacketSender()), player, s)
        host.open()

        val thumb = s.nodeAt(5, 5)!!
        assertTrue(SurfaceFocus.grab(player.uuid, thumb))

        // Tick 1: ray on the surface, over the thumb.
        host.tick()
        assertEquals(1, moves.size, "grab must be fed while the ray is on the surface")

        // Tick 2: ray off the surface entirely.
        player.eye = OFF_SURFACE_EYE
        host.tick()
        assertEquals(2, moves.size, "grab must keep being fed once the ray leaves the surface")

        SurfaceFocus.clear(player.uuid)
    }

    @Test
    fun pointerExitFiresOnTheNodeThatWasHoveredWhenTheRayLeaves() {
        val s = surface()
        var exitFired = false
        s.layout { root ->
            val node = WidgetNode("node", PxSize(10, 10))
            node.onEvent = { e ->
                if (e is SurfaceEvent.PointerExit) exitFired = true
                EventResult.PASS
            }
            root.addChild(node)
        }
        val player = FakePlayer()
        val host = SurfaceHost(platform(RecordingPacketSender()), player, s)
        host.open()

        host.tick() // hovers "node"
        assertFalse(exitFired, "no exit should fire while still hovering")

        player.eye = OFF_SURFACE_EYE
        host.tick()
        assertTrue(exitFired, "the previously-hovered node must receive PointerExit when the ray leaves")

        SurfaceFocus.clear(player.uuid)
    }

    @Test
    fun unchangedHoverRepaintsOnlyThePointerNotTheLayers() {
        val s = surface()
        s.layout { root -> root.addChild(WidgetNode("node", PxSize(10, 10))) }
        val player = FakePlayer()
        val sender = RecordingPacketSender()
        val host = SurfaceHost(platform(sender), player, s)
        host.open()

        host.tick() // first hover: null -> "node", so this one DOES repaint
        val metadataAfterFirstTick = sender.updateMetadataCount

        host.tick() // same pixel, same node: hover unchanged
        val metadataAfterSecondTick = sender.updateMetadataCount

        // The only packet an unchanged-hover tick may cause is showPointer's
        // own metadata update for the cursor entity -- never a full layer
        // repaint. That is the ~140-packets/sec/viewer rule this task exists
        // to protect.
        assertEquals(
            1, metadataAfterSecondTick - metadataAfterFirstTick,
            "an unchanged hover must update only the pointer entity, not repaint the layers"
        )

        SurfaceFocus.clear(player.uuid)
    }

    @Test
    fun movingThePointerReusesTheEntityRatherThanRespawningIt() {
        // Respawning every tick would churn an entity 20x/second and defeat the
        // reason the pointer is a separate entity at all.
        val s = surface()
        val player = FakePlayer()
        val sender = RecordingPacketSender()
        val host = SurfaceHost(platform(sender), player, s)
        host.open()
        val spawnBefore = sender.spawnCount
        val metadataBefore = sender.updateMetadataCount

        host.showPointer(1, 1)
        host.showPointer(2, 2)

        assertEquals(1, sender.spawnCount - spawnBefore, "the second call must reuse the entity, not respawn it")
        assertEquals(2, sender.updateMetadataCount - metadataBefore, "both calls must push a metadata update")

        SurfaceFocus.clear(player.uuid)
    }

    @Test
    fun closeDestroysEveryEntityAndClearsFocus() {
        val s = surface()
        s.layout { root -> root.addChild(WidgetNode("node", PxSize(10, 10))) }
        val player = FakePlayer()
        val sender = RecordingPacketSender()
        val host = SurfaceHost(platform(sender), player, s)
        host.open()
        host.tick() // spawns the pointer too, and populates SurfaceFocus state

        assertNotEquals(SurfaceFocus.State(), SurfaceFocus.state(player.uuid), "tick should have recorded hover state")
        val idsBeforeClose = host.entities().map { it.entityId }.toSet()
        assertTrue(idsBeforeClose.isNotEmpty(), "fixture sanity: layers + pointer must exist before close()")

        host.close()

        assertEquals(idsBeforeClose, sender.destroyedIds.toSet(), "close() must destroy layers + backing + pointer")
        assertEquals(SurfaceFocus.State(), SurfaceFocus.state(player.uuid), "close() must clear this player's focus state")
    }
}
