package io.schemat.displaykit.surface.layout

import kotlin.test.Test
import kotlin.test.assertEquals

private class Fixed(id: String, val w: Int, val h: Int) : BaseSurfaceNode(id) {
    override fun measureSelf(c: PxConstraints): PxSize = c.constrain(PxSize(w, h))
}

class FlexNodeTest {

    @Test
    fun rowPlacesChildrenLeftToRightWithGaps() {
        val row = FlexNode("row", FlexDirection.ROW, gap = 4)
        val a = Fixed("a", 10, 10); val b = Fixed("b", 20, 10)
        row.addChild(a); row.addChild(b)
        row.measure(PxConstraints.exactly(100, 10))
        row.place(PxOffset.Zero)
        assertEquals(0, a.rect().x)
        assertEquals(14, b.rect().x)
    }

    @Test
    fun growWeightsSplitLeftoverSpaceExactly() {
        val row = FlexNode("row", FlexDirection.ROW)
        val a = Fixed("a", 0, 10).also { it.flexGrow = 1 }
        val b = Fixed("b", 0, 10).also { it.flexGrow = 1 }
        row.addChild(a); row.addChild(b)
        row.measure(PxConstraints.exactly(100, 10))
        row.place(PxOffset.Zero)
        assertEquals(50, a.rect().w)
        assertEquals(50, b.rect().w)
        assertEquals(100, a.rect().w + b.rect().w)
    }

    @Test
    fun anIndivisibleRemainderGoesToTheEarliestChildren() {
        // 100 across 3 equal weights is 33.33. Children must still sum to 100,
        // or a row of cells ends up narrower than its container -- the drift
        // this whole layout is integer to avoid.
        val row = FlexNode("row", FlexDirection.ROW)
        val kids = (0 until 3).map { Fixed("k$it", 0, 10).also { k -> k.flexGrow = 1 } }
        kids.forEach { row.addChild(it) }
        row.measure(PxConstraints.exactly(100, 10))
        row.place(PxOffset.Zero)
        assertEquals(listOf(34, 33, 33), kids.map { it.rect().w })
        assertEquals(100, kids.sumOf { it.rect().w })
    }

    @Test
    fun ninePixelCellsInThreeFortySixStillSumExactly() {
        // The picker's real case.
        val row = FlexNode("row", FlexDirection.ROW)
        val kids = (0 until 9).map { Fixed("k$it", 0, 18).also { k -> k.flexGrow = 1 } }
        kids.forEach { row.addChild(it) }
        row.measure(PxConstraints.exactly(346, 18))
        row.place(PxOffset.Zero)
        assertEquals(346, kids.sumOf { it.rect().w })
        // and no child is more than one pixel off any other
        assertEquals(1, kids.maxOf { it.rect().w } - kids.minOf { it.rect().w })
    }

    @Test
    fun paddingInsetsChildren() {
        val row = FlexNode("row", FlexDirection.ROW)
        row.padding = PxPadding.all(5)
        val a = Fixed("a", 10, 10)
        row.addChild(a)
        row.measure(PxConstraints.exactly(100, 20))
        row.place(PxOffset.Zero)
        assertEquals(5, a.rect().x)
        assertEquals(5, a.rect().y)
    }

    @Test
    fun columnStacksTopToBottom() {
        val col = FlexNode("col", FlexDirection.COLUMN, gap = 2)
        val a = Fixed("a", 10, 10); val b = Fixed("b", 10, 6)
        col.addChild(a); col.addChild(b)
        col.measure(PxConstraints.exactly(20, 100))
        col.place(PxOffset.Zero)
        assertEquals(0, a.rect().y)
        assertEquals(12, b.rect().y)
    }

    @Test
    fun crossAxisCenterCentresWithinTheLine() {
        val row = FlexNode("row", FlexDirection.ROW, crossAxis = CrossAxis.CENTER)
        val a = Fixed("a", 10, 10)
        row.addChild(a)
        row.measure(PxConstraints.exactly(100, 30))
        row.place(PxOffset.Zero)
        assertEquals(10, a.rect().y)
    }
}
