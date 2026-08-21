package io.schemat.displaykit.surface

import io.schemat.displaykit.sprite.GlyphPlacement
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

    /** One crop of one sprite at one ascent — the variant key. */
    private data class Ask(val id: SpriteId, val srcX: Int, val srcY: Int, val ascent: Int)

    /** Hands out a distinct codepoint per variant, with no pack involved. */
    private class FakeSource : SliceGlyphSource {
        val handed = LinkedHashMap<Ask, Int>()
        val requests = mutableListOf<SpriteId>()
        private var next = 0xF8000
        override fun request(id: SpriteId) { requests += id }
        override fun codepointFor(id: SpriteId, srcX: Int, srcY: Int, ascent: Int): Int =
            handed.getOrPut(Ask(id, srcX, srcY, ascent)) { next++ }
        // Null exercises NineSlicePainter's declared-width fallback;
        // the fake has no pixels for a real trimmed measurement.
        override fun advanceFor(id: SpriteId, srcX: Int, srcY: Int): Int? = null

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
        // y=9 (not 0): the button's 3px borders need ascent 7 on their
        // natural row, which only a >=7px-tall glyph satisfies without a row
        // fallback. At y=0 that fallback has nowhere to go (row 0 is already
        // the floor), so the top border would be the one spec-acknowledged
        // case GlyphPlacement warns and skips rather than draws — see
        // aTopBorderFlushAgainstCanvasYZeroIsSkippedRatherThanMisplaced below.
        // y=9 gives every crop a fallback row, so nothing is skipped here.
        NineSlicePainter.paint(c, button, Rect(0, 9, 400, 60))
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
    // which is fixed per font entry. So a crop drawn with N distinct ascents
    // needs its OWN variant per ascent -- exactly what SpriteCanvas.draw does
    // for whole sprites. Without it every such region collapses onto its
    // row's baseline.

    @Test
    fun aRegionAtANonMultipleOfTheLineHeightYAsksForTheMatchingAscent() {
        val c = SpriteCanvas(400, 200)
        // rect.y = 8, so the top row of crops (corners + top edge, all 3px
        // tall under NineSlice(3,3,3,3)) lands at y = 8: natural row 0,
        // required ascent = 7 - 8 = -1, satisfiable without a row fallback.
        NineSlicePainter.paint(c, button, Rect(0, 8, 400, 60))

        val topRow = src.handed.keys.filter { it.srcY == 0 }
        assertTrue(topRow.isNotEmpty(), "the frame must place top-edge crops")
        assertTrue(
            topRow.all { it.ascent == -1 },
            "crops drawn at y=8 (height 3) must request ascent -1, got ${topRow.map { it.ascent }}"
        )
    }

    @Test
    fun everyAskedAscentMatchesGlyphPlacementForItsDrawnYAndCropHeight() {
        val rect = Rect(0, 9, 400, 220)
        val c = SpriteCanvas(400, 260)
        NineSlicePainter.paint(c, button, rect)

        // Independently reconstruct what every crop SHOULD have asked for
        // from NineSliceLayout's own placements, rather than re-deriving a
        // formula inline -- this is what proves drawGlyph doesn't drift from
        // GlyphPlacement, the same guarantee the production code documents.
        val regions = NineSliceLayout.regionsFor(button, rect.w, rect.h)
        val expected = regions.mapNotNull { p ->
            GlyphPlacement.resolve(rect.y + p.dstY, p.srcH)?.let { placement ->
                Ask(button.id, p.srcX, p.srcY, placement.ascent)
            }
        }.toSet()

        assertEquals(expected, src.handed.keys)
        assertTrue(
            expected.map { it.ascent }.toSet().size > 1,
            "this layout must exercise more than one distinct ascent, or it proves nothing"
        )
    }

    @Test
    fun theSameCropAtTwoAscentsGetsTwoDistinctCodepoints() {
        val a = SpriteCanvas(400, 200)
        val b = SpriteCanvas(400, 200)
        // Corner crop is 3px tall; y=4 -> ascent 3 (its max), y=7 -> ascent 0.
        NineSlicePainter.paint(a, button, Rect(0, 4, 400, 60))
        NineSlicePainter.paint(b, button, Rect(0, 7, 400, 60))

        val atAscent3 = Ask(button.id, 0, 0, 3)
        val atAscent0 = Ask(button.id, 0, 0, 0)
        assertNotNull(src.handed[atAscent3])
        assertNotNull(src.handed[atAscent0])
        assertNotEquals(
            src.handed[atAscent3], src.handed[atAscent0],
            "one codepoint cannot carry two ascents; each ascent needs its own"
        )
    }

    @Test
    fun aTopBorderFlushAgainstCanvasYZeroIsSkippedRatherThanMisplaced() {
        // The button's top border/corners are 3px tall. At canvas y=0 the
        // client's own ascent<=height limit makes their top unreachable: row
        // 0's minimum achievable top is 7-height=4, never 0, and row 0 is
        // already the floor GlyphPlacement can fall back to. This is the
        // spec-acknowledged case: warn once, draw nothing for that region,
        // rather than emit an ascent the client would refuse to load.
        SpriteDiagnostics.reset()
        val c = SpriteCanvas(400, 60)
        NineSlicePainter.paint(c, button, Rect(0, 0, 400, 60))

        val allRegions = NineSliceLayout.regionsFor(button, 400, 60).size
        assertTrue(
            c.itemCount() < allRegions,
            "the unsatisfiable top border/corners must be skipped, not drawn wrong"
        )
        assertTrue(SpriteDiagnostics.warnings().isNotEmpty(), "skipping must warn")
        SpriteDiagnostics.reset()
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
