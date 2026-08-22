package io.schemat.displaykit.surface.workspace

import io.schemat.displaykit.surface.layout.BoxNode
import io.schemat.displaykit.surface.layout.PxConstraints
import io.schemat.displaykit.surface.layout.PxOffset
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.SurfaceNode
import io.schemat.displaykit.surface.layout.WidgetNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Splits must actually divide their region, in exact integer pixels.
 *
 * Asserted against real measured geometry rather than against the node tree's
 * shape: a split that builds the right nodes but lays them out one pixel short
 * is exactly the class of defect that has repeatedly reached the client here.
 */
class PaneLayoutTest {

    private fun pane(id: String): SurfaceNode =
        WidgetNode(id, PxSize(1, 1)) { _, _ -> }

    /** Lay [tree] out in a [w] x [h] region and report each pane's rect. */
    private fun place(tree: PaneTree, w: Int, h: Int): Map<String, IntArray> {
        val root = BoxNode("root")
        root.addChild(PaneLayout.build(tree) { pane(it) })
        root.measure(PxConstraints.exactly(w, h))
        root.place(PxOffset.Zero)

        val out = LinkedHashMap<String, IntArray>()
        fun walk(n: SurfaceNode) {
            if (n is WidgetNode) {
                val r = n.rect()
                out[n.id] = intArrayOf(r.x, r.y, r.w, r.h)
            }
            n.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    @Test
    fun aLoneLeafFillsTheRegion() {
        val r = place(PaneTree.Leaf("a"), 300, 200)
        assertEquals(listOf("a"), r.keys.toList())
        assertTrue(r["a"]!!.contentEquals(intArrayOf(0, 0, 300, 200)), r["a"]!!.toList().toString())
    }

    @Test
    fun aSideBySideSplitDividesTheWidthAndKeepsFullHeight() {
        val tree = PaneTree.Leaf("a").split("a", SplitAxis.SIDE_BY_SIDE, "b")
        val r = place(tree, 300, 200)

        assertTrue(r["a"]!!.contentEquals(intArrayOf(0, 0, 150, 200)), r["a"]!!.toList().toString())
        assertTrue(r["b"]!!.contentEquals(intArrayOf(150, 0, 150, 200)), r["b"]!!.toList().toString())
    }

    @Test
    fun aStackedSplitDividesTheHeightAndKeepsFullWidth() {
        val tree = PaneTree.Leaf("a").split("a", SplitAxis.STACKED, "b")
        val r = place(tree, 300, 200)

        assertTrue(r["a"]!!.contentEquals(intArrayOf(0, 0, 300, 100)), r["a"]!!.toList().toString())
        assertTrue(r["b"]!!.contentEquals(intArrayOf(0, 100, 300, 100)), r["b"]!!.toList().toString())
    }

    @Test
    fun theRatioIsHonouredInPixels() {
        val tree = PaneTree.Leaf("a").split("a", SplitAxis.SIDE_BY_SIDE, "b", ratio = 70)
        val r = place(tree, 300, 200)

        assertEquals(210, r["a"]!![2])
        assertEquals(90, r["b"]!![2])
    }

    @Test
    fun panesAlwaysSumToExactlyTheRegionEvenWhenIndivisible() {
        // The remainder rule is the whole reason ratios are integers. A row of
        // panes that sums to one pixel short of its container is the defect
        // this guards -- invisible at a glance, permanent once shipped.
        val tree = PaneTree.Leaf("a")
            .split("a", SplitAxis.SIDE_BY_SIDE, "b", ratio = 33)
            .split("b", SplitAxis.SIDE_BY_SIDE, "c", ratio = 33)

        for (width in 100..140) {
            val r = place(tree, width, 50)
            val total = r.values.sumOf { it[2] }
            assertEquals(width, total, "panes must sum to exactly $width, got $total")

            // ...and tile it with no gap and no overlap.
            val sorted = r.values.sortedBy { it[0] }
            var cursor = 0
            for (rect in sorted) {
                assertEquals(cursor, rect[0], "pane must start where the previous ended (w=$width)")
                cursor += rect[2]
            }
        }
    }

    @Test
    fun nestedSplitsOnBothAxesTileTheRegion() {
        // a | (b over c)
        val tree = PaneTree.Leaf("a")
            .split("a", SplitAxis.SIDE_BY_SIDE, "b")
            .split("b", SplitAxis.STACKED, "c")
        val r = place(tree, 400, 200)

        assertTrue(r["a"]!!.contentEquals(intArrayOf(0, 0, 200, 200)), r["a"]!!.toList().toString())
        assertTrue(r["b"]!!.contentEquals(intArrayOf(200, 0, 200, 100)), r["b"]!!.toList().toString())
        assertTrue(r["c"]!!.contentEquals(intArrayOf(200, 100, 200, 100)), r["c"]!!.toList().toString())

        val area = r.values.sumOf { it[2].toLong() * it[3] }
        assertEquals(400L * 200, area, "the panes must exactly cover the region, no gaps or overlap")
    }

    @Test
    fun everyPaneGetsBuiltExactlyOnce() {
        val built = mutableListOf<String>()
        val tree = PaneTree.Leaf("a")
            .split("a", SplitAxis.SIDE_BY_SIDE, "b")
            .split("b", SplitAxis.STACKED, "c")

        PaneLayout.build(tree) { id -> built += id; pane(id) }

        assertEquals(listOf("a", "b", "c"), built)
    }

    @Test
    fun splitNodeIdsAreUniqueSoHitTestingCanTellThemApart() {
        // Two nodes sharing an id makes focus routing and hit-testing
        // ambiguous, which is the same failure a duplicate pane id causes.
        val tree = PaneTree.Leaf("a")
            .split("a", SplitAxis.SIDE_BY_SIDE, "b")
            .split("b", SplitAxis.STACKED, "c")
            .split("c", SplitAxis.SIDE_BY_SIDE, "d")

        val ids = mutableListOf<String>()
        fun walk(n: SurfaceNode) {
            ids += n.id
            n.children.forEach(::walk)
        }
        walk(PaneLayout.build(tree) { pane(it) })

        assertEquals(ids.size, ids.toSet().size, "duplicate node ids: $ids")
    }
}
