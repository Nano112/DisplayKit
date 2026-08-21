package io.schemat.displaykit.surface

import io.schemat.displaykit.surface.layout.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class Node(id: String, val w: Int, val h: Int) : BaseSurfaceNode(id) {
    override fun measureSelf(c: PxConstraints): PxSize = c.constrain(PxSize(w, h))
}

class SurfaceEventsTest {

    private fun tree(): Triple<Node, Node, Node> {
        val root = Node("root", 100, 100)
        val mid = Node("mid", 60, 60)
        val leaf = Node("leaf", 20, 20)
        root.addChild(mid); mid.addChild(leaf)
        // Node is a leaf test double that does not lay out its children, so
        // each node must be measured individually — a real container would
        // cascade this itself, but the double does not.
        root.measure(PxConstraints.exactly(100, 100))
        mid.measure(PxConstraints.exactly(60, 60))
        leaf.measure(PxConstraints.exactly(20, 20))
        root.place(PxOffset.Zero)
        mid.place(PxOffset(0, 0)); leaf.place(PxOffset(0, 0))
        return Triple(root, mid, leaf)
    }

    @Test
    fun anUnhandledEventBubblesToTheAncestor() {
        val (root, mid, leaf) = tree()
        val seen = mutableListOf<String>()
        leaf.onEvent = { seen += "leaf"; EventResult.PASS }
        mid.onEvent = { seen += "mid"; EventResult.PASS }
        root.onEvent = { seen += "root"; EventResult.CONSUMED }
        val handler = SurfaceEvents.dispatch(root, SurfaceEvent.Scroll(5, 5, 1))
        assertEquals(listOf("leaf", "mid", "root"), seen)
        assertEquals("root", handler?.id)
    }

    @Test
    fun consumingStopsTheBubble() {
        val (root, mid, leaf) = tree()
        val seen = mutableListOf<String>()
        leaf.onEvent = { seen += "leaf"; EventResult.PASS }
        mid.onEvent = { seen += "mid"; EventResult.CONSUMED }
        root.onEvent = { seen += "root"; EventResult.CONSUMED }
        SurfaceEvents.dispatch(root, SurfaceEvent.Click(5, 5, PointerButton.LEFT))
        assertEquals(listOf("leaf", "mid"), seen, "root must not see a consumed event")
    }

    @Test
    fun aNodeWithNoHandlerIsSkippedNotTreatedAsConsuming() {
        val (root, mid, leaf) = tree()
        val seen = mutableListOf<String>()
        // mid has no handler at all
        leaf.onEvent = { seen += "leaf"; EventResult.PASS }
        root.onEvent = { seen += "root"; EventResult.CONSUMED }
        SurfaceEvents.dispatch(root, SurfaceEvent.Scroll(5, 5, -1))
        assertEquals(listOf("leaf", "root"), seen)
    }

    @Test
    fun anEventOutsideTheTreeDispatchesToNothing() {
        val (root, _, _) = tree()
        var called = false
        root.onEvent = { called = true; EventResult.CONSUMED }
        assertNull(SurfaceEvents.dispatch(root, SurfaceEvent.Click(500, 500, PointerButton.LEFT)))
        assertEquals(false, called)
    }

    @Test
    fun scrollOverANonScrollableCellReachesTheScrollPaneAbove() {
        // The case that motivated bubbling: a grid cell inside a scroll pane
        // does not handle scroll, so the pane must get it.
        val root = Node("root", 100, 100)
        val pane = ScrollNode("pane").also { it.stepPx = 10 }
        val cell = Node("cell", 40, 40)
        root.addChild(pane); pane.addChild(cell)
        repeat(9) { pane.addChild(Node("filler$it", 40, 40)) }
        // root is a leaf test double: measuring/placing it does not cascade
        // into pane, a real container, so pane must be measured and placed
        // explicitly or its rect stays zero-sized and hitTest misses it.
        root.measure(PxConstraints.exactly(100, 100))
        pane.measure(PxConstraints.exactly(100, 100))
        root.place(PxOffset.Zero)
        pane.place(PxOffset.Zero)

        cell.onEvent = { EventResult.PASS }
        var scrolled = 0
        pane.onEvent = { e -> if (e is SurfaceEvent.Scroll) { scrolled += e.delta }; EventResult.CONSUMED }

        SurfaceEvents.dispatch(root, SurfaceEvent.Scroll(5, 5, 2))
        assertEquals(2, scrolled)
    }
}
