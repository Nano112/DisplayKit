package io.schemat.displaykit.surface

import io.schemat.displaykit.sprite.NineSlice
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NineSliceLayoutTest {

    /** gui/widget/button: 200x20, border 3 on every side. Centre source is 194x14. */
    private val button = SpriteEntry(
        id = SpriteId("gui", "widget/button"),
        width = 200, height = 20,
        texture = "minecraft:gui/sprites/widget/button.png",
        nineSlice = NineSlice(left = 3, top = 3, right = 3, bottom = 3)
    )

    /** gui/widget/tab: 130x24, bottom border 0 -> the bottom row is degenerate. */
    private val tab = SpriteEntry(
        id = SpriteId("gui", "widget/tab"),
        width = 130, height = 24,
        texture = "minecraft:gui/sprites/widget/tab.png",
        nineSlice = NineSlice(left = 2, top = 2, right = 2, bottom = 0)
    )

    @Test
    fun minimumSizeIsTheSpritesOwnSize() {
        assertEquals(200 to 20, NineSliceLayout.minimumSize(button))
    }

    @Test
    fun rejectsAFrameSmallerThanOneWholeTile() {
        val e = assertFailsWith<IllegalArgumentException> {
            NineSliceLayout.regionsFor(button, 199, 20)
        }
        assertTrue(e.message!!.contains("200"), "message should name the minimum: ${e.message}")
    }

    @Test
    fun atExactlySpriteSizeEveryRegionIsPlacedOnce() {
        val r = NineSliceLayout.regionsFor(button, 200, 20)
        assertEquals(9, r.size)
    }

    @Test
    fun cornersSitAtTheFourExtremes() {
        val r = NineSliceLayout.regionsFor(button, 400, 60)
        // corners are emitted last, so they are the final four entries
        val corners = r.takeLast(4)
        val positions = corners.map { it.dstX to it.dstY }.toSet()
        assertTrue((0 to 0) in positions, "top-left")
        assertTrue((400 - 3 to 0) in positions, "top-right")
        assertTrue((0 to 60 - 3) in positions, "bottom-left")
        assertTrue((400 - 3 to 60 - 3) in positions, "bottom-right")
        for (c in corners) {
            assertEquals(3, c.srcW); assertEquals(3, c.srcH)
        }
    }

    @Test
    fun everyRegionStaysInsideTheTargetRect() {
        for ((w, h) in listOf(200 to 20, 201 to 21, 400 to 60, 613 to 137)) {
            for (p in NineSliceLayout.regionsFor(button, w, h)) {
                assertTrue(p.dstX >= 0 && p.dstY >= 0, "negative origin at ${w}x$h: $p")
                assertTrue(p.dstX + p.srcW <= w, "overflows right at ${w}x$h: $p")
                assertTrue(p.dstY + p.srcH <= h, "overflows bottom at ${w}x$h: $p")
            }
        }
    }

    @Test
    fun theFinalEdgeTileIsFlushWithTheFarEdge() {
        // 400 wide: interior spans x=3..397, centre source is 194 wide.
        // Tiles at 3 and 197 cover to 391; the last must sit at 397-194=203, not 391.
        val r = NineSliceLayout.regionsFor(button, 400, 20)
        val topEdge = r.filter { it.srcY == 0 && it.srcH == 3 && it.srcW == 194 }
        assertTrue(topEdge.isNotEmpty(), "expected top-edge tiles")
        assertEquals(400 - 3, topEdge.maxOf { it.dstX + it.srcW }, "last top tile must end at the border")
    }

    @Test
    fun tilesCoverTheInteriorWithNoUncoveredColumn() {
        val w = 613
        val r = NineSliceLayout.regionsFor(button, w, 20)
        val covered = BooleanArray(w)
        for (p in r) for (x in p.dstX until p.dstX + p.srcW) covered[x] = true
        assertTrue(covered.all { it }, "every column must be painted")
    }

    @Test
    fun degenerateRowsAreOmitted() {
        // tab has bottom = 0, so the three bottom-row regions must not appear
        val r = NineSliceLayout.regionsFor(tab, 260, 48)
        assertTrue(r.none { it.srcH == 0 }, "no zero-height region")
        assertTrue(r.none { it.dstY + it.srcH > 48 }, "nothing past the bottom")
    }

    @Test
    fun aSpriteWithoutNineSliceMetadataYieldsNothing() {
        val plain = button.copy(nineSlice = null)
        assertEquals(0, NineSliceLayout.regionsFor(plain, 400, 60).size)
    }
}
