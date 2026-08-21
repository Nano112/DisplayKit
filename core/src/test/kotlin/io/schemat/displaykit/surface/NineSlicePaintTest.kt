package io.schemat.displaykit.surface

import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.NineSlice
import io.schemat.displaykit.sprite.SpriteCanvas
import io.schemat.displaykit.sprite.SpriteDiagnostics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NineSlicePaintTest {

    private val button = SpriteEntry(
        id = SpriteId("gui", "widget/button"),
        width = 200, height = 20,
        texture = "minecraft:gui/sprites/widget/button.png",
        nineSlice = NineSlice(3, 3, 3, 3)
    )

    /** One crop of one sprite at one vertical offset — the variant key. */
    private data class Ask(val id: SpriteId, val srcX: Int, val srcY: Int, val yOffset: Int)

    /** Hands out a distinct codepoint per variant, with no pack involved. */
    private class FakeSource : SliceGlyphSource {
        val handed = LinkedHashMap<Ask, Int>()
        val requests = mutableListOf<SpriteId>()
        private var next = 0xF8000
        override fun request(id: SpriteId) { requests += id }
        override fun codepointFor(id: SpriteId, srcX: Int, srcY: Int, yOffset: Int): Int =
            handed.getOrPut(Ask(id, srcX, srcY, yOffset)) { next++ }
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

    // A bitmap glyph's vertical placement is baked into its provider `ascent`,
    // which is fixed per font entry. So a crop drawn at a y that is not a
    // multiple of TextMetrics.LINE_HEIGHT_PX needs its OWN variant, whose
    // ascent absorbs the within-row remainder — exactly what
    // SpriteCanvas.draw does for whole sprites. Without it every such region
    // collapses onto its row's baseline.

    @Test
    fun aRegionAtANonMultipleOfTenYAsksForTheMatchingNegativeOffset() {
        val c = SpriteCanvas(400, 200)
        // rect.y = 9, so the top row of crops lands at y = 9: remainder 9.
        NineSlicePainter.paint(c, button, Rect(0, 9, 400, 60))

        val topRow = src.handed.keys.filter { it.srcY == 0 }
        assertTrue(topRow.isNotEmpty(), "the frame must place top-edge crops")
        assertTrue(
            topRow.all { it.yOffset == -9 },
            "crops drawn at y=9 must request yOffset -9, got ${topRow.map { it.yOffset }}"
        )
    }

    @Test
    fun everyAskedOffsetIsMinusTheWithinRowRemainderOfItsDrawnY() {
        val c = SpriteCanvas(400, 260)
        NineSlicePainter.paint(c, button, Rect(0, 9, 400, 220))

        // Each drawn item's y must be consistent with an offset that was asked
        // for, and every offset asked for must be -(y % LINE_HEIGHT_PX).
        val offsetsAsked = src.handed.keys.map { it.yOffset }.toSet()
        val expected = c.itemPositions().map { (_, y) -> -(y % TextMetrics.LINE_HEIGHT_PX) }.toSet()
        assertEquals(expected, offsetsAsked)
        assertTrue(
            offsetsAsked.any { it != 0 },
            "this layout must exercise at least one non-zero offset, or it proves nothing"
        )
    }

    @Test
    fun theSameCropAtTwoRemaindersGetsTwoDistinctCodepoints() {
        val a = SpriteCanvas(400, 200)
        val b = SpriteCanvas(400, 200)
        NineSlicePainter.paint(a, button, Rect(0, 0, 400, 60))   // remainder 0
        NineSlicePainter.paint(b, button, Rect(0, 3, 400, 60))   // remainder 3

        val corner = Ask(button.id, 0, 0, 0)
        val shifted = Ask(button.id, 0, 0, -3)
        assertNotNull(src.handed[corner])
        assertNotNull(src.handed[shifted])
        assertNotEquals(
            src.handed[corner], src.handed[shifted],
            "one codepoint cannot carry two ascents; each offset needs its own"
        )
    }

    @Test
    fun aSpriteWithNoNineSliceMetadataWarnsOnceRatherThanDrawingNothing() {
        SpriteDiagnostics.reset()
        val plain = SpriteEntry(
            id = SpriteId("gui", "hud/hotbar"),
            width = 182, height = 22,
            texture = "minecraft:gui/sprites/hud/hotbar.png",
            nineSlice = null
        )
        val c = SpriteCanvas(400, 60)
        NineSlicePainter.paint(c, plain, Rect(0, 0, 400, 60))

        assertEquals(0, c.itemCount(), "nothing may be drawn")
        assertTrue(
            SpriteDiagnostics.warnings().any { it.contains("gui/hud/hotbar") },
            "the warning must name the sprite, got ${SpriteDiagnostics.warnings()}"
        )

        // once-only: a second paint must not add a second warning
        val before = SpriteDiagnostics.warnings().size
        NineSlicePainter.paint(c, plain, Rect(0, 0, 400, 60))
        assertEquals(before, SpriteDiagnostics.warnings().size)
        SpriteDiagnostics.reset()
    }
}
