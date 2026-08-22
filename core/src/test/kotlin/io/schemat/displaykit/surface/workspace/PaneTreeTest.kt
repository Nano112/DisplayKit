package io.schemat.displaykit.surface.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arrangement model behind a tmux-style screen.
 *
 * Pure structure: no rendering, no Minecraft, no server. That is the point --
 * every defect this subsystem has shipped reached a client because the logic
 * that caused it was only reachable through a render pass.
 */
class PaneTreeTest {

    private fun leaf(id: String) = PaneTree.Leaf(id)

    @Test
    fun aLoneLeafIsTheWholeScreen() {
        val t = leaf("a")
        assertEquals(listOf("a"), t.panes())
        assertTrue(t.contains("a"))
        assertTrue(!t.contains("b"))
    }

    @Test
    fun splittingPutsTheNewPaneSecond() {
        // Every multiplexer puts the new pane right of, or below, the one you
        // were looking at. Repeated splits are only predictable if this holds.
        val t = leaf("a").split("a", SplitAxis.SIDE_BY_SIDE, "b")

        assertEquals(listOf("a", "b"), t.panes())
        val split = t as PaneTree.Split
        assertEquals(SplitAxis.SIDE_BY_SIDE, split.axis)
        assertEquals(leaf("a"), split.first)
        assertEquals(leaf("b"), split.second)
    }

    @Test
    fun splittingANestedPaneLeavesItsSiblingsAlone() {
        val t = leaf("a")
            .split("a", SplitAxis.SIDE_BY_SIDE, "b")
            .split("b", SplitAxis.STACKED, "c")

        assertEquals(listOf("a", "b", "c"), t.panes())
        val outer = t as PaneTree.Split
        assertEquals(leaf("a"), outer.first, "the untouched sibling must be identical")
        val inner = outer.second as PaneTree.Split
        assertEquals(SplitAxis.STACKED, inner.axis)
    }

    @Test
    fun splittingAnUnknownPaneChangesNothing() {
        val t = leaf("a").split("a", SplitAxis.SIDE_BY_SIDE, "b")
        assertEquals(t, t.split("nope", SplitAxis.STACKED, "c"))
    }

    @Test
    fun aDuplicatePaneIdIsRejected() {
        // A duplicate would make panes() ambiguous and break focus routing
        // silently, far from the mistake.
        val t = leaf("a").split("a", SplitAxis.SIDE_BY_SIDE, "b")
        assertFailsWith<IllegalArgumentException> { t.split("a", SplitAxis.STACKED, "b") }
    }

    @Test
    fun closingCollapsesTheSplitAndPromotesTheSibling() {
        val t = leaf("a").split("a", SplitAxis.SIDE_BY_SIDE, "b")

        assertEquals(leaf("a"), t.close("b"), "the survivor takes the whole region")
        assertEquals(leaf("b"), t.close("a"))
    }

    @Test
    fun closingTheOnlyPaneEmptiesTheScreen() {
        // null rather than a placeholder pane: the caller decides whether an
        // empty screen closes, and inventing a pane would hide that decision.
        assertNull(leaf("a").close("a"))
    }

    @Test
    fun closingDeepInTheTreeKeepsTheRestIntact() {
        val t = leaf("a")
            .split("a", SplitAxis.SIDE_BY_SIDE, "b")
            .split("b", SplitAxis.STACKED, "c")

        val after = t.close("c")

        assertEquals(listOf("a", "b"), after?.panes())
        assertEquals(SplitAxis.SIDE_BY_SIDE, (after as PaneTree.Split).axis)
    }

    @Test
    fun closingAnUnknownPaneChangesNothing() {
        val t = leaf("a").split("a", SplitAxis.SIDE_BY_SIDE, "b")
        assertEquals(t, t.close("nope"))
    }

    @Test
    fun closingEveryPaneOneByOneEndsEmpty() {
        var t: PaneTree? = leaf("a")
            .split("a", SplitAxis.SIDE_BY_SIDE, "b")
            .split("b", SplitAxis.STACKED, "c")

        for (id in listOf("b", "a", "c")) {
            t = t?.close(id)
            assertTrue(t?.contains(id) != true, "'$id' must be gone after closing it")
        }
        assertNull(t, "closing every pane must empty the screen, not strand one")
    }

    @Test
    fun resizingTheFirstPaneSetsTheRatioDirectly() {
        val t = leaf("a").split("a", SplitAxis.SIDE_BY_SIDE, "b").resize("a", 70)
        assertEquals(70, (t as PaneTree.Split).ratio)
    }

    @Test
    fun resizingTheSecondPaneTakesTheComplement() {
        // The split stores the FIRST child's share, so asking for 70% of the
        // second pane must store 30. Getting this backwards inverts every
        // drag on a second pane.
        val t = leaf("a").split("a", SplitAxis.SIDE_BY_SIDE, "b").resize("b", 70)
        assertEquals(30, (t as PaneTree.Split).ratio)
    }

    @Test
    fun aResizeDragPastTheEdgeStopsAtTheEdge() {
        // This is called on every pointer move during a drag. Running past
        // the edge must clamp, not throw -- and must never produce a ratio
        // Split's own constructor would reject.
        val base = leaf("a").split("a", SplitAxis.SIDE_BY_SIDE, "b")

        for (asked in listOf(-500, -1, 0, 5, 95, 100, 500)) {
            val first = (base.resize("a", asked) as PaneTree.Split).ratio
            val second = (base.resize("b", asked) as PaneTree.Split).ratio
            assertTrue(
                first in PaneTree.MIN_RATIO..PaneTree.MAX_RATIO,
                "resize('a', $asked) produced an illegal ratio $first"
            )
            assertTrue(
                second in PaneTree.MIN_RATIO..PaneTree.MAX_RATIO,
                "resize('b', $asked) produced an illegal ratio $second"
            )
        }
    }

    @Test
    fun anOutOfRangeRatioIsRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            PaneTree.Split(SplitAxis.STACKED, leaf("a"), leaf("b"), ratio = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PaneTree.Split(SplitAxis.STACKED, leaf("a"), leaf("b"), ratio = 100)
        }
    }

    @Test
    fun resizingLeavesOtherSplitsAlone() {
        val t = leaf("a")
            .split("a", SplitAxis.SIDE_BY_SIDE, "b")
            .split("b", SplitAxis.STACKED, "c")
            .resize("c", 25)

        val outer = t as PaneTree.Split
        assertEquals(50, outer.ratio, "the outer split must keep its own ratio")
        assertEquals(75, (outer.second as PaneTree.Split).ratio)
    }

    @Test
    fun nextPaneCyclesInOrderAndWraps() {
        val t = leaf("a")
            .split("a", SplitAxis.SIDE_BY_SIDE, "b")
            .split("b", SplitAxis.STACKED, "c")

        assertEquals("b", t.nextPane("a"))
        assertEquals("c", t.nextPane("b"))
        assertEquals("a", t.nextPane("c"), "the last pane wraps to the first")
    }

    @Test
    fun nextPaneOnASinglePaneIsANoOp() {
        assertEquals("a", leaf("a").nextPane("a"))
    }

    @Test
    fun nextPaneOnAnUnknownPaneIsNull() {
        assertNull(leaf("a").nextPane("nope"))
    }

    @Test
    fun everyOperationLeavesTheOriginalUntouched() {
        // Immutability is what makes split/close total and safe to call from
        // an event handler that may itself be re-entered.
        val original = leaf("a").split("a", SplitAxis.SIDE_BY_SIDE, "b")
        // Built independently rather than copied, so this also pins the shape
        // split() produces rather than merely comparing it against itself.
        val expected = PaneTree.Split(SplitAxis.SIDE_BY_SIDE, leaf("a"), leaf("b"), ratio = 50)

        original.split("a", SplitAxis.STACKED, "c")
        original.close("a")
        original.resize("a", 80)

        assertEquals(expected, original)
    }
}
