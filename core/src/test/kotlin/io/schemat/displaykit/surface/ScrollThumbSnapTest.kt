package io.schemat.displaykit.surface

import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [snapToLinePitch] is the fix for the recurring "scrollbar thumb forces a
 * resource-pack download loop" bug: a sprite's vertical placement is baked
 * into its font ascent, so an unsnapped thumb Y lands on a fresh ascent phase
 * on almost every move. It used to be re-implemented (correctly) in
 * `PickerWindow` alone; `TerminalWidget` drew its own unsnapped thumb and
 * shipped the loop again. The fix now lives in [scrollThumb] itself, the one
 * place a thumb is ever drawn, so no future caller can omit it.
 */
class ScrollThumbSnapTest {

    @Test
    fun snapsDownToTheNearestLinePitchMultiple() {
        assertEquals(0, snapToLinePitch(0))
        assertEquals(0, snapToLinePitch(9))
        assertEquals(10, snapToLinePitch(10))
        assertEquals(10, snapToLinePitch(19))
        assertEquals(20, snapToLinePitch(20))
        assertEquals(120, snapToLinePitch(127))
    }

    /** One crop of the thumb sprite at one ascent -- the slice variant key. */
    private data class SliceAsk(val srcX: Int, val srcY: Int, val ascent: Int)

    private class FakeSliceSource : SliceGlyphSource {
        val handed = LinkedHashMap<SliceAsk, Int>()
        private var next = 0xF9000
        override fun request(id: SpriteId) {}
        override fun codepointFor(id: SpriteId, srcX: Int, srcY: Int, ascent: Int): Int =
            handed.getOrPut(SliceAsk(srcX, srcY, ascent)) { next++ }
        override fun advanceFor(id: SpriteId, srcX: Int, srcY: Int): Int? = null
        /**
         * Slice variants PLUS whole-sprite variants OF THE THUMB.
         *
         * Which allocator a frame uses is a function of its size: at its own
         * native size a sprite is drawn as one whole glyph rather than cut
         * into nine, because cutting and re-placing a sprite that needs no
         * stretching can only introduce error. The thumb is 6x32 and is
         * usually drawn at exactly 6x32, so counting slices alone made these
         * tests read zero and pass vacuously.
         *
         * Scoped to the thumb sprite rather than counting the whole glyph
         * table: a paint also warms unrelated sprites (every `fill` warms its
         * filler at all row phases), and that background growth would look
         * like the leak these tests exist to detect.
         */
        fun variantCount() = handed.size +
            SpriteGlyphs.requested().count { it.entry.id.sprite == "widget/scroller" }
    }

    private lateinit var slices: FakeSliceSource

    @BeforeTest
    fun setUp() {
        slices = FakeSliceSource()
        SliceGlyphSource.installed = slices
    }

    @AfterTest
    fun tearDown() {
        SliceGlyphSource.installed = null
    }

    /**
     * Proves [scrollThumb] actually applies the snap when drawing, not just
     * that the pure function above rounds correctly in isolation.
     *
     * y=101 and y=109 both floor to the line-pitch multiple 100, so a thumb
     * requested at either must resolve to the identical ascent and share
     * every slice variant -- if [scrollThumb] were still passing `rect.y`
     * through unsnapped, these two would land on different phases (1 and 9)
     * and each allocate their own, which the negative control below (drawing
     * the identical two rects directly through [SurfacePainter.frame],
     * bypassing the snap) confirms they actually do.
     */
    @Test
    fun scrollThumbSnapsAnUnsnappedRectBeforeDrawing() {
        val surface = Surface(40, 200, Vec3d.ZERO, targetWidthBlocks = 3f)

        surface.paint { scrollThumb(Rect(0, 101, 6, 32)) }
        val afterFirst = slices.variantCount()
        assertTrue(afterFirst > 0, "drawing a real thumb must allocate something, or this test is vacuous")

        surface.paint { scrollThumb(Rect(0, 109, 6, 32)) }
        assertEquals(
            afterFirst, slices.variantCount(),
            "y=101 and y=109 both snap to 100 and must share every slice variant"
        )

        // Negative control: the identical two Ys, drawn directly through
        // `frame` so nothing snaps them, DO land on different phases and DO
        // allocate new slices -- proving the stability above comes from
        // scrollThumb's snap, not from some other property of these two Ys.
        val sprite = SpriteIndex.bundled.get(SpriteId("gui", "widget/scroller"))
            ?: error("gui:widget/scroller missing from the bundled sprite index")
        surface.paint { frame(sprite, Rect(0, 101, 6, 32)) }
        val rawBaseline = slices.variantCount()
        surface.paint { frame(sprite, Rect(0, 109, 6, 32)) }
        assertTrue(
            slices.variantCount() > rawBaseline,
            "negative control: an unsnapped y=101 vs y=109 draw must land on different phases and allocate new slices"
        )
    }
}
