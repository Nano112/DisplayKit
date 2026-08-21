package io.schemat.displaykit.surface.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class Block(id: String, val h: Int) : BaseSurfaceNode(id) {
    override fun measureSelf(c: PxConstraints): PxSize = c.constrain(PxSize(c.maxW, h))
}

class ScrollNodeTest {

    private fun paneOf(rows: Int, rowH: Int = 20, viewport: Int = 100): ScrollNode {
        val pane = ScrollNode("pane")
        pane.stepPx = rowH
        repeat(rows) { pane.addChild(Block("r$it", rowH)) }
        pane.measure(PxConstraints.exactly(50, viewport))
        pane.place(PxOffset.Zero)
        return pane
    }

    @Test
    fun maxScrollIsContentMinusViewport() {
        val pane = paneOf(rows = 10)   // 200px of content in a 100px viewport
        assertEquals(100, pane.maxScroll())
    }

    @Test
    fun contentShorterThanTheViewportCannotScroll() {
        val pane = paneOf(rows = 2)    // 40px in 100px
        assertEquals(0, pane.maxScroll())
        assertFalse(pane.scrollBy(1))
    }

    @Test
    fun scrollingClampsAtBothEnds() {
        val pane = paneOf(rows = 10)
        assertFalse(pane.scrollBy(-1), "already at the top")
        assertTrue(pane.scrollBy(3))
        assertEquals(60, pane.scrollPx)
        assertTrue(pane.scrollBy(99))
        assertEquals(100, pane.scrollPx, "clamped to maxScroll")
        assertFalse(pane.scrollBy(1), "already at the bottom")
    }

    @Test
    fun onlyFullyVisibleChildrenAreEmitted() {
        // The canvas has NO clipping, so a partially visible row would spill
        // outside the pane. Emitting whole rows only is what keeps the window
        // sealed; scrolling therefore steps by stepPx.
        val pane = paneOf(rows = 10)
        assertEquals(listOf("r0", "r1", "r2", "r3", "r4"), pane.visibleChildren().map { it.id })
        pane.scrollBy(1)
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), pane.visibleChildren().map { it.id })
    }

    @Test
    fun hitTestIgnoresScrolledOutChildren() {
        val pane = paneOf(rows = 10)
        pane.scrollBy(1)
        // r0 has scrolled out; a point in the viewport must never resolve to it.
        val hit = pane.hitTest(10, 10)
        assertEquals("r1", hit?.id)
    }

    @Test
    fun scrollToSnapsToAStepBoundary() {
        // A dragged thumb produces arbitrary offsets. Landing between rows
        // would leave no child flush with the viewport edge and blank it.
        val pane = paneOf(rows = 10)          // rowH 20, viewport 100, maxScroll 100
        assertTrue(pane.scrollTo(27))
        assertEquals(20, pane.scrollPx, "27 snaps to the nearest 20")
        assertEquals(5, pane.visibleChildren().size, "a full viewport, not a blank one")
        assertTrue(pane.scrollTo(99))
        assertEquals(100, pane.scrollPx, "snaps to 100 and clamps to maxScroll")
    }
}
