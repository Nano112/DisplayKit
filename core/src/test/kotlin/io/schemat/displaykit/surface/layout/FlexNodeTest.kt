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

    @Test
    fun spaceBetweenDistributesLeftoverGapPixelsExactly() {
        // slack that does not divide evenly must still reach the far edge.
        val row = FlexNode("row", FlexDirection.ROW, mainAxis = MainAxis.SPACE_BETWEEN)
        val kids = (0 until 3).map { Fixed("k$it", 10, 10) }
        kids.forEach { row.addChild(it) }
        row.measure(PxConstraints.exactly(35, 10))   // 35 - 30 = 5 slack across 2 gaps
        row.place(PxOffset.Zero)
        assertEquals(0, kids[0].rect().x)
        assertEquals(35, kids[2].rect().right, "last child must reach the far edge")
    }

    @Test
    fun crossAxisCenterAppliesToAGrowingChildToo() {
        // A growing child must not be force-filled on the cross axis, or
        // CENTER/END silently do nothing to it.
        val row = FlexNode("row", FlexDirection.ROW, crossAxis = CrossAxis.CENTER)
        val a = Fixed("a", 0, 10).also { it.flexGrow = 1 }
        row.addChild(a)
        row.measure(PxConstraints.exactly(100, 30))
        row.place(PxOffset.Zero)
        assertEquals(100, a.rect().w, "still grows on the main axis")
        assertEquals(10, a.rect().h, "keeps its natural cross size")
        assertEquals(10, a.rect().y, "and is centred within the 30px line")
    }

    @Test
    fun stretchSizesANonGrowingChildToTheCrossAxis() {
        // A zero-intrinsic-width child in a COLUMN is a natural thing to write
        // for a full-width title bar. Without working STRETCH it stays 0 wide
        // and any painter that tiles a sprite into it throws.
        val col = FlexNode("col", FlexDirection.COLUMN, crossAxis = CrossAxis.STRETCH)
        val title = Fixed("title", 0, 16)
        col.addChild(title)
        col.measure(PxConstraints.exactly(346, 264))
        col.place(PxOffset.Zero)
        assertEquals(346, title.rect().w, "STRETCH must fill the cross axis")
        assertEquals(16, title.rect().h, "and leave the main axis alone")
    }

    @Test
    fun stretchDoesNotOverrideAnExplicitSize() {
        val col = FlexNode("col", FlexDirection.COLUMN, crossAxis = CrossAxis.STRETCH)
        val fixed = Fixed("fixed", 50, 16).also { it.width = 50 }
        col.addChild(fixed)
        col.measure(PxConstraints.exactly(346, 264))
        col.place(PxOffset.Zero)
        assertEquals(50, fixed.rect().w, "an explicit width still wins over STRETCH")
    }
}
