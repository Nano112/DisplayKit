package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PacketSender
import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.platform.Scheduler
import io.schemat.displaykit.platform.TextInput
import io.schemat.displaykit.render.TextComponent
import io.schemat.displaykit.render.VirtualEntity
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceHost
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeyedColumnTest {
    private data class Item(val id: String, val value: String)

    @Test
    fun reconcileRetainsRowsUpdatesValuesAndDisposesRemovals() {
        val created = mutableMapOf<String, WidgetNode>()
        val values = mutableMapOf<String, String>()
        val disposed = mutableListOf<String>()
        val column = KeyedColumn(
            id = "list",
            initialItems = listOf(Item("a", "A"), Item("b", "B"), Item("c", "C")),
            key = Item::id,
            row = { rowId, item ->
                val node = WidgetNode(rowId, PxSize(100, 10))
                created[item.id] = node
                values[item.id] = item.value
                KeyedRow(
                    node,
                    update = { values[it.id] = it.value },
                    dispose = { disposed += item.id }
                )
            }
        )
        val originalA = created.getValue("a")
        val originalB = created.getValue("b")

        val result = column.reconcile(
            listOf(Item("b", "B2"), Item("d", "D"), Item("a", "A"))
        )

        assertTrue(result.changed)
        assertEquals(setOf("d"), result.added)
        assertEquals(setOf("b"), result.updated)
        assertEquals(setOf("c"), result.removed)
        assertTrue(result.reordered)
        assertEquals("B2", values["b"])
        assertEquals(listOf("c"), disposed)
        assertTrue(originalA === created["a"])
        assertTrue(originalB === created["b"])
        assertEquals(listOf("b", "d", "a"), column.keys())
    }

    @Test
    fun duplicateOrIncorrectRowIdentityFailsFast() {
        assertFailsWith<IllegalArgumentException> {
            KeyedColumn(
                "duplicates",
                listOf(Item("same", "1"), Item("same", "2")),
                Item::id
            ) { rowId, _ -> KeyedRow(WidgetNode(rowId)) }
        }
        assertFailsWith<IllegalArgumentException> {
            KeyedColumn("wrong", listOf(Item("a", "A")), Item::id) { _, _ ->
                KeyedRow(WidgetNode("manual-id"))
            }
        }
    }

    @Test
    fun hostReconciliationKeepsUnaffectedRowEntityIds() {
        val sender = RecordingSender()
        val player = FakePlayer()
        val labels = mutableMapOf<String, String>()
        val column = KeyedColumn(
            "data",
            listOf(Item("a", "A"), Item("b", "B"), Item("c", "C")),
            Item::id
        ) { rowId, item ->
            labels[item.id] = item.value
            KeyedRow(
                WidgetNode(rowId, PxSize(100, 10)) { painter, rect ->
                    painter.label(labels.getValue(item.id), rect.x, rect.y)
                },
                update = { labels[it.id] = it.value }
            )
        }
        val surface = Surface(100, 40, Vec3d.ZERO, 1f).also {
            it.renderMode = RenderMode.ENTITIES
            it.layout { root -> root.addChild(column.node) }
            it.paintTree()
        }
        val host = SurfaceHost(platform(sender), player, surface)
        host.open()
        val before = host.entities().associateBy { it.reconcileKey }
        val spawnCount = sender.spawns

        column.reconcile(listOf(Item("b", "B2"), Item("d", "D"), Item("a", "A")))
        surface.paintTree()
        host.repaint()
        val after = host.entities().associateBy { it.reconcileKey }

        fun rowEntity(map: Map<String?, VirtualEntity>, key: String): VirtualEntity =
            assertNotNull(map.entries.firstOrNull { it.key?.contains("data-row-$key") == true }?.value)

        assertEquals(rowEntity(before, "a").entityId, rowEntity(after, "a").entityId)
        assertEquals(rowEntity(before, "b").entityId, rowEntity(after, "b").entityId)
        assertEquals(spawnCount + 1, sender.spawns, "only the new keyed row should spawn")
        assertEquals(1, sender.destroyed.size, "only the removed keyed row should be destroyed")
        assertTrue(sender.metadata >= 1, "changed retained row should update metadata")
    }

    private class RecordingSender : PacketSender {
        var spawns = 0
        var metadata = 0
        val destroyed = mutableListOf<Int>()
        override fun spawnEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) { spawns++ }
        override fun updateMetadata(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) { metadata++ }
        override fun destroyEntities(entityIds: Collection<Int>, viewerUUIDs: Collection<UUID>) { destroyed += entityIds }
        override fun teleportEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>) {}
        override fun updateMetadataBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {}
        override fun updateTransformBatch(entities: Collection<VirtualEntity>, viewerUUIDs: Collection<UUID>) {}
        override fun spawnCarrierEntity(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) {}
        override fun teleportCarrier(entityId: Int, position: Vec3d, viewerUUIDs: Collection<UUID>) {}
        override fun moveCarrier(entityId: Int, deltaX: Double, deltaY: Double, deltaZ: Double, viewerUUIDs: Collection<UUID>) {}
        override fun setPassengers(vehicleEntityId: Int, passengerEntityIds: IntArray, viewerUUIDs: Collection<UUID>) {}
    }

    private class FakePlayer(override val uuid: UUID = UUID.randomUUID()) : PlayerRef {
        override val name = "keyed-test"
        override fun eyePosition() = Vec3d(0.0, 0.0, -2.0)
        override fun lookDirection() = Vec3d(0.0, 0.0, 1.0)
        override fun isOnline() = true
        override fun sendMessage(message: TextComponent) {}
    }

    private fun platform(sender: PacketSender): PlatformProvider = object : PlatformProvider {
        override val logger: Logger = Logger.getLogger("keyed-test")
        override val packetSender = sender
        override val scheduler: Scheduler get() = error("unused")
        override val textInput: TextInput get() = error("unused")
        override fun getPlayer(uuid: UUID): PlayerRef? = null
        override fun getOnlinePlayers(): Collection<PlayerRef> = emptyList()
    }
}
