package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.PointerButton
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.layout.SurfaceNode
import io.schemat.displaykit.state.StateScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataWidgetsTest {
    @Test
    fun definitionListUsesOneSharedColumnBoundaryAndStableRows() {
        val list = DefinitionList(
            id = "defs",
            initialEntries = listOf(
                DefinitionEntry("cpu", "CPU", "4 cores"),
                DefinitionEntry("ram", "Memory", "16 GB")
            ),
            style = DefinitionListStyle(width = 180, labelWidth = 70)
        )
        val surface = Surface(200, 80, Vec3d.ZERO, 2f).also {
            it.renderMode = RenderMode.ENTITIES
            it.layout { root -> root.addChild(list.node) }
            it.paintTree()
        }
        val cpu = assertNotNull(surface.root?.find("defs-row-cpu"))
        val ram = assertNotNull(surface.root?.find("defs-row-ram"))
        assertEquals(cpu.rect().x, ram.rect().x)
        assertEquals(cpu.rect().w, ram.rect().w)

        val result = list.reconcile(
            listOf(
                DefinitionEntry("cpu", "CPU", "8 cores"),
                DefinitionEntry("disk", "Disk", "120 GB")
            )
        )
        assertEquals(setOf("cpu"), result.updated)
        assertEquals(setOf("disk"), result.added)
        assertEquals(setOf("ram"), result.removed)
    }

    @Test
    fun dataListResolvesColumnsOnceAndRetainsRowsAcrossReordering() {
        data class Row(val name: String, val count: Int)
        val table = DataList(
            id = "table",
            columns = listOf(
                DataColumn<Row>("name", "Name", weight = 2, text = Row::name),
                DataColumn(
                    "count",
                    "Count",
                    width = 40,
                    alignment = DataCellAlignment.END,
                    text = { it.count.toString() }
                )
            ),
            initialEntries = listOf(
                DataEntry("a", Row("Alpha", 1)),
                DataEntry("b", Row("Beta", 2))
            ),
            style = DataListStyle(width = 180, columnGap = 6)
        )
        val originalA = table.node.find("table-body-row-a")

        val result = table.reconcile(
            listOf(
                DataEntry("b", Row("Beta", 3)),
                DataEntry("a", Row("Alpha", 1))
            )
        )

        assertTrue(result.reordered)
        assertEquals(setOf("b"), result.updated)
        assertTrue(originalA === table.node.find("table-body-row-a"))
        assertEquals(listOf("b", "a"), table.keys())
    }

    @Test
    fun dataListCanOwnStableInteractiveRowsWithoutFeatureHitArithmetic() {
        var selected: String? = null
        val table = DataList(
            id = "modules",
            columns = listOf(DataColumn<String>("name", "Module", text = { it })),
            initialEntries = listOf(DataEntry("clock", "Clock")),
            style = DataListStyle(width = 180),
            selectedKey = { selected },
            isHovered = { false },
            onSelected = { selected = it.key }
        )
        val surface = Surface(200, 60, Vec3d.ZERO, 2f).also {
            it.renderMode = RenderMode.ENTITIES
            it.layout { root -> root.addChild(table.node) }
        }
        val row = assertNotNull(surface.root?.find("modules-body-row-clock"))
        val rect = row.rect()
        assertEquals(
            row,
            surface.dispatch(
                SurfaceEvent.Click(rect.centeredX(1), rect.centeredY(1), PointerButton.LEFT),
                target = row
            )
        )
        assertEquals("clock", selected)
    }

    @Test
    fun pagedDataListOwnsClampedNavigationAndSliceReconciliation() {
        val scope = StateScope()
        val entries = mutableListOf(
            DataEntry("a", "Alpha"), DataEntry("b", "Beta"), DataEntry("c", "Gamma")
        )
        val paged = PagedDataListView(
            id = "paged",
            scope = scope,
            columns = listOf(DataColumn<String>("name", "Name", text = { it })),
            entries = { entries.toList() },
            pageSize = 2,
            dataStyle = DataListStyle(width = 180),
            isHovered = { false }
        )
        val surface = Surface(200, 120, Vec3d.ZERO, 2f).also {
            it.renderMode = RenderMode.ENTITIES
            it.layout { root -> root.addChild(paged.node) }
        }
        assertEquals(listOf("a", "b"), paged.list.keys())

        val next = assertNotNull(surface.root?.find("paged-pager-next"))
        val rect = next.rect()
        surface.dispatch(
            SurfaceEvent.Click(rect.centeredX(1), rect.centeredY(1), PointerButton.LEFT),
            target = next
        )
        assertEquals(listOf("c"), paged.list.keys())

        entries.removeLast()
        paged.reconcile()
        assertEquals(0, paged.pagination.pageIndex.value)
        assertEquals(listOf("a", "b"), paged.list.keys())
        scope.close()
    }

    private fun SurfaceNode.find(id: String): SurfaceNode? {
        if (this.id == id) return this
        return children.firstNotNullOfOrNull { it.find(id) }
    }
}
