package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.surface.layout.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SurfaceLayoutTest {

    private fun surface() = Surface(346, 264, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 3f)

    @Test
    fun layoutMeasuresTheRootToTheCanvasBounds() {
        val s = surface()
        s.layout { root ->
            val col = FlexNode("col", FlexDirection.COLUMN)
            root.addChild(col)
        }
        val root = assertNotNull(s.root)
        assertEquals(346, root.rect().w)
        assertEquals(264, root.rect().h)
    }

    @Test
    fun paintingWalksTheTreeAndDrawsWidgetLeaves() {
        val s = surface()
        var painted: Rect? = null
        s.layout { root ->
            val w = WidgetNode("w", PxSize(50, 20)) { _, rect -> painted = rect }
            w.padding = PxPadding.Zero
            root.addChild(w)
        }
        s.paintTree()
        assertEquals(Rect(0, 0, 50, 20), painted)
    }

    @Test
    fun dispatchRoutesACanvasPointToTheRightLeaf() {
        val s = surface()
        var clicked: String? = null
        s.layout { root ->
            val row = FlexNode("row", FlexDirection.ROW)
            for (i in 0 until 3) {
                val cell = WidgetNode("cell$i", PxSize(20, 20))
                cell.onEvent = { e ->
                    if (e is SurfaceEvent.Click) { clicked = cell.id; EventResult.CONSUMED }
                    else EventResult.PASS
                }
                row.addChild(cell)
            }
            root.addChild(row)
        }
        s.dispatch(SurfaceEvent.Click(25, 5, PointerButton.LEFT))
        assertEquals("cell1", clicked)
    }

    @Test
    fun relayoutAfterAContentChangeDoesNotLeakOldNodes() {
        val s = surface()
        s.layout { root -> root.addChild(WidgetNode("a", PxSize(10, 10))) }
        s.layout { root -> root.addChild(WidgetNode("b", PxSize(10, 10))) }
        assertEquals(listOf("b"), s.root!!.children.map { it.id })
    }

    @Test
    fun aZeroWidthWidgetInAStretchColumnIsNonZeroAfterLayout() {
        // Guards the PickerWindow crash: a WidgetNode with a zero-intrinsic
        // width, meant to span its COLUMN parent's full width, must actually
        // come out non-zero once real Surface.layout runs -- not just in the
        // narrower FlexNode-only unit tests. A painter that tiles a sprite
        // into a 0-wide rect throws (Surface.Painter.fill's 16x16 minimum).
        val s = surface()
        s.layout { root ->
            val col = FlexNode("col", FlexDirection.COLUMN, crossAxis = CrossAxis.STRETCH)
            val title = WidgetNode("title", PxSize(0, 16))
            col.addChild(title)
            root.addChild(col)
        }
        val title = s.root!!.children.single().children.single()
        assertEquals(346, title.rect().w, "must not stay 0-wide")
        assertEquals(16, title.rect().h)
    }
}
