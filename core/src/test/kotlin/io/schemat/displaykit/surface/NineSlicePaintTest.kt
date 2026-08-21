package io.schemat.displaykit.surface

import io.schemat.displaykit.sprite.NineSlice
import io.schemat.displaykit.sprite.SpriteCanvas
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NineSlicePaintTest {

    private val button = SpriteEntry(
        id = SpriteId("gui", "widget/button"),
        width = 200, height = 20,
        texture = "minecraft:gui/sprites/widget/button.png",
        nineSlice = NineSlice(3, 3, 3, 3)
    )

    /** Hands out a distinct codepoint per (sprite, srcX, srcY) with no pack involved. */
    private class FakeSource : SliceGlyphSource {
        val handed = LinkedHashMap<Triple<SpriteId, Int, Int>, Int>()
        val requests = mutableListOf<SpriteId>()
        private var next = 0xF8000
        override fun request(id: SpriteId) { requests += id }
        override fun codepointFor(id: SpriteId, srcX: Int, srcY: Int): Int =
            handed.getOrPut(Triple(id, srcX, srcY)) { next++ }
    }

    private lateinit var src: FakeSource

    @BeforeTest fun setUp() { src = FakeSource(); SliceGlyphSource.installed = src }
    @AfterTest fun tearDown() { SliceGlyphSource.installed = null }

    @Test
    fun paintingAFrameRequestsItsSlices() {
        val c = SpriteCanvas(400, 60)
        NineSlicePainter.paint(c, button, Rect(0, 0, 400, 60))
        assertTrue(button.id in src.requests)
    }

    @Test
    fun everyPlacedRegionBecomesADrawnItem() {
        val c = SpriteCanvas(400, 60)
        NineSlicePainter.paint(c, button, Rect(0, 0, 400, 60))
        val expected = NineSliceLayout.regionsFor(button, 400, 60).size
        assertEquals(expected, c.itemCount())
    }

    @Test
    fun theFrameIsOffsetByTheRectOrigin() {
        val c = SpriteCanvas(400, 120)
        NineSlicePainter.paint(c, button, Rect(20, 40, 300, 60))
        assertTrue(c.itemPositions().all { (x, y) -> x >= 20 && y >= 40 },
            "no region may be drawn above or left of the rect origin")
    }

    @Test
    fun tooSmallAFrameFailsWithTheMinimumInTheMessage() {
        val c = SpriteCanvas(100, 100)
        val e = kotlin.test.assertFailsWith<IllegalArgumentException> {
            NineSlicePainter.paint(c, button, Rect(0, 0, 50, 10))
        }
        assertTrue(e.message!!.contains("200"))
    }

    @Test
    fun withNoSourceInstalledPaintingIsANoOpRatherThanACrash() {
        SliceGlyphSource.installed = null
        val c = SpriteCanvas(400, 60)
        NineSlicePainter.paint(c, button, Rect(0, 0, 400, 60))
        assertEquals(0, c.itemCount())
    }
}
