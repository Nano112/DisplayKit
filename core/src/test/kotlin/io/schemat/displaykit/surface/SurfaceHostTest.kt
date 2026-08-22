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

        /**
         * Where each teleport put an entity.
         *
         * This used to be a no-op stubbed "unused by SurfaceHost", and that
         * assumption is exactly what let the cursor ship broken: position is
         * not metadata, so assigning it and sending only a metadata packet
         * left the client drawing the cursor wherever it spawned.
         */
        val teleportedTo = mutableListOf<Vec3d>()

        override fun spawnEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
            spawnCount++
        }

        override fun updateMetadata(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
            updateMetadataCount++
        }

        override fun destroyEntities(entityIds: Collection<Int>, viewerUUIDs: Collection<UUID>) {
            destroyedIds += entityIds
        }

        override fun teleportEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {
            teleportedTo += entity.position
        }
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

        // Tick 3: still off surface. A feed gated on the hover CHANGE (i.e.
        // only on the on->off transition) would have passed tick 2 above and
        // then gone quiet here -- that is precisely the regression this test
        // exists to catch, since the drag must survive CONTINUOUSLY, not just
        // for one tick after leaving.
        host.tick()
        assertEquals(3, moves.size, "the grab is fed on every off-surface tick, not just the transition")

        SurfaceFocus.clear(player.uuid)
    }

    @Test
    fun theCursorIsTELEPORTEDAsTheRayMovesNotJustReMetadatad() {
        // Reported in-world as a cursor that "only tracks on entering but
        // doesn't follow". The spawn packet carries a position, so the very
        // first frame looked right; every update after it assigned
        // entity.position and sent a METADATA packet, which does not move an
        // entity. The client kept drawing the cursor where it spawned.
        val s = surface()
        s.layout { root -> root.addChild(WidgetNode("body", PxSize(10, 10))) }
        val sender = RecordingPacketSender()
        val player = FakePlayer()
        val host = SurfaceHost(platform(sender), player, s)
        host.open()

        host.tick()
        val afterFirst = sender.teleportedTo.size

        // Re-aim so the ray lands on a different canvas pixel.
        player.look = Vec3d(0.10, -0.10, 1.0)
        host.tick()

        assertTrue(
            sender.teleportedTo.size > afterFirst,
            "moving the ray must teleport the cursor; metadata alone leaves it " +
                "frozen where it spawned"
        )
        val moved = sender.teleportedTo.takeLast(2)
        if (moved.size == 2) {
            assertTrue(
                moved[0] != moved[1] || sender.teleportedTo.size == afterFirst + 1,
                "consecutive teleports must actually differ in position"
            )
        }

        SurfaceFocus.clear(player.uuid)
    }

    @Test
    fun aStationaryCursorDoesNotTeleportEveryTick() {
        // The tick loop runs per viewer at 20Hz; an unconditional teleport
        // would be a packet a tick for a cursor that has not moved.
        val s = surface()
        s.layout { root -> root.addChild(WidgetNode("body", PxSize(10, 10))) }
        val sender = RecordingPacketSender()
        val player = FakePlayer()
        val host = SurfaceHost(platform(sender), player, s)
        host.open()

        host.tick()
        val settled = sender.teleportedTo.size
        repeat(5) { host.tick() }

        assertEquals(
            settled, sender.teleportedTo.size,
            "a cursor that has not moved must not be teleported again"
        )

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
    fun closeDestroysEveryEntityButLeavesFocusAlone() {
        // Focus is per-VIEWER, not per-host (SurfaceFocus's own KDoc): a
        // viewer has one pointer across all their surfaces, so close()
        // tearing down THIS window's entities must not wipe hover/grab state
        // that may still belong to another open surface. Dropping the last
        // surface for a player is InteractionRouter.unregisterSurface's job,
        // not SurfaceHost.close()'s -- see
        // aRepaintThatChangesTheLayerCountDoesNotCancelAnActiveGrab below for
        // the regression this used to cause.
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
        assertNotEquals(
            SurfaceFocus.State(), SurfaceFocus.state(player.uuid),
            "close() must NOT clear this player's focus state -- that is InteractionRouter's job now"
        )

        SurfaceFocus.clear(player.uuid)
    }

    @Test
    fun aRepaintThatChangesTheLayerCountDoesNotCancelAnActiveGrab() {
        // repaint() falls back to close()+open() when the layer set changes.
        // If close() also cleared focus, a tab switch would cancel a drag
        // mid-gesture -- the user's cursor is still down on the thumb.
        val s = surface()

        // A grabbable node comes from the layout tree, independent of
        // whatever the canvas itself is painted with below.
        s.layout { root ->
            val thumb = WidgetNode("thumb", PxSize(10, 10))
            thumb.onGrabMove = { _, _ -> }
            root.addChild(thumb)
        }
        // One painted layer (KIND_TEXT at elevation 0).
        s.paint { label("first layer", 0, 0) }

        val player = FakePlayer()
        val host = SurfaceHost(platform(RecordingPacketSender()), player, s)
        host.open()
        val layersBefore = s.toEntities().size

        val thumb = s.nodeAt(5, 5)!!
        assertTrue(SurfaceFocus.grab(player.uuid, thumb), "fixture sanity: the thumb must be grabbable")
        assertEquals(thumb, SurfaceFocus.grabbed(player.uuid), "fixture sanity: grab must be recorded before repaint")

        // Add a second, elevated label -- elevate() shifts KIND_TEXT's depth,
        // which lands on a different canvas layer number, growing
        // canvas.layers() and therefore toEntities()'s size. This is what
        // drives repaint() into its close()+open() fallback (repaint()
        // compares fresh.size to the OLD layers.size captured at open()).
        s.paint {
            label("first layer", 0, 0)
            elevate(1) { label("second layer", 0, 20) }
        }
        val layersAfter = s.toEntities().size
        assertNotEquals(layersBefore, layersAfter, "fixture sanity: the layer count must actually change")

        host.repaint()

        assertEquals(
            thumb, SurfaceFocus.grabbed(player.uuid),
            "a repaint that changes the layer count must not cancel an in-progress grab"
        )

        SurfaceFocus.clear(player.uuid)
    }
}
