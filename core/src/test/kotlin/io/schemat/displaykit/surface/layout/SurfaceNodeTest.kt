package io.schemat.displaykit.surface.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

private class TestNode(id: String, private val w: Int, private val h: Int) : BaseSurfaceNode(id) {
    override fun measureSelf(c: PxConstraints): PxSize = c.constrain(PxSize(w, h))
}

class SurfaceNodeTest {

    @Test
    fun constraintsClampBothAxes() {
        val c = PxConstraints(minW = 10, maxW = 100, minH = 5, maxH = 50)
        assertEquals(PxSize(10, 5), c.constrain(PxSize(0, 0)))
        assertEquals(PxSize(100, 50), c.constrain(PxSize(999, 999)))
        assertEquals(PxSize(40, 20), c.constrain(PxSize(40, 20)))
    }

    @Test
    fun absoluteRectAccumulatesThroughParents() {
        val root = TestNode("root", 200, 100)
        val child = TestNode("child", 50, 20)
        root.addChild(child)
        root.measure(PxConstraints.exactly(200, 100))
        child.measure(PxConstraints.upTo(200, 100))   // a container would do this
        root.place(PxOffset(10, 10))
        child.place(PxOffset(5, 7))
        // place() offsets are parent-relative; rect() is absolute.
        assertEquals(io.schemat.displaykit.surface.Rect(15, 17, 50, 20), child.rect())
    }

    @Test
    fun hitTestReturnsTheDeepestContainingNode() {
        val root = TestNode("root", 100, 100)
        val mid = TestNode("mid", 50, 50)
        val leaf = TestNode("leaf", 10, 10)
        root.addChild(mid); mid.addChild(leaf)
        root.measure(PxConstraints.exactly(100, 100))
        mid.measure(PxConstraints.upTo(100, 100))   // a container would do this
        leaf.measure(PxConstraints.upTo(100, 100))  // a container would do this
        root.place(PxOffset(0, 0))
        mid.place(PxOffset(10, 10)); leaf.place(PxOffset(5, 5))
        // leaf occupies absolute 15,15..25,25
        assertEquals("leaf", root.hitTest(20, 20)?.id)
        assertEquals("mid", root.hitTest(40, 40)?.id)
        assertEquals("root", root.hitTest(80, 80)?.id)
        assertNull(root.hitTest(200, 200))
    }

    @Test
    fun hitTestPrefersTheLastAddedOverlappingSibling() {
        // Painter's algorithm: later children draw on top, so they take the hit.
        val root = TestNode("root", 100, 100)
        val under = TestNode("under", 50, 50)
        val over = TestNode("over", 50, 50)
        root.addChild(under); root.addChild(over)
        root.measure(PxConstraints.exactly(100, 100))
        under.measure(PxConstraints.upTo(100, 100))  // a container would do this
        over.measure(PxConstraints.upTo(100, 100))   // a container would do this
        root.place(PxOffset(0, 0))
        under.place(PxOffset(0, 0)); over.place(PxOffset(0, 0))
        assertEquals("over", root.hitTest(10, 10)?.id)
    }

    @Test
    fun addChildSetsTheParentLink() {
        val root = TestNode("root", 10, 10)
        val child = TestNode("child", 5, 5)
        root.addChild(child)
        assertSame(root, child.parent)
    }
}
