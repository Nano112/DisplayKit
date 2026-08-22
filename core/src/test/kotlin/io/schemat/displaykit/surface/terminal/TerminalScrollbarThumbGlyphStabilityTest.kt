package io.schemat.displaykit.surface.terminal

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.sprite.NineSlice
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.SliceGlyphSource
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.PxPadding
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.ScrollNode
import io.schemat.displaykit.surface.layout.SurfaceNode
import io.schemat.displaykit.surface.layout.WidgetNode
import io.schemat.displaykit.surface.scrollThumb
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors `ScrollbarThumbGlyphStabilityTest`'s picker-shaped invariant for a
 * terminal-shaped scrollbar: a single scrolling column of text rows plus a
 * draggable thumb, rather than a grid.
 *
 * Unlike `PickerWindow`, [TerminalWidget] needs no live Fabric server, so
 * [fullScrollThenFullDragAllocatesNoNewGlyphsOrSlices] drives the actual
 * production class rather than a hand-built stand-in -- this is the direct
 * regression test for the reported bug: appending scrollback lines moves the
 * thumb, and before `scrollThumb` snapped its own Y, that forced a resource-
 * pack download loop on every append.
 */
class TerminalScrollbarThumbGlyphStabilityTest {

    /** One crop of the thumb sprite at one ascent -- the slice variant key. */
    private data class SliceAsk(val srcX: Int, val srcY: Int, val ascent: Int)

    private class FakeSliceSource : SliceGlyphSource {
        val handed = LinkedHashMap<SliceAsk, Int>()
        private var next = 0xF9000
        override fun request(id: SpriteId) {}
        override fun codepointFor(id: SpriteId, srcX: Int, srcY: Int, ascent: Int): Int =
            handed.getOrPut(SliceAsk(srcX, srcY, ascent)) { next++ }
        override fun advanceFor(id: SpriteId, srcX: Int, srcY: Int): Int? = null
        fun variantCount() = handed.size
    }

    private lateinit var slices: FakeSliceSource

    @BeforeTest
    fun setUp() {
        SpriteGlyphs.clear()
        slices = FakeSliceSource()
        SliceGlyphSource.installed = slices
    }

    @AfterTest
    fun tearDown() {
        SpriteGlyphs.clear()
        SliceGlyphSource.installed = null
    }

    private fun SurfaceNode.findById(target: String): SurfaceNode? {
        if (id == target) return this
        for (c in children) c.findById(target)?.let { return it }
        return null
    }

    @Test
    fun fullScrollThenFullDragAllocatesNoNewGlyphsOrSlices() {
        val model = TerminalModel(columns = 40)
        repeat(80) { model.append("line $it") }

        val surface = Surface(300, 120, Vec3d.ZERO, targetWidthBlocks = 3f)
        val widget = TerminalWidget(model, contentWidth = 280)

        surface.layout { root -> widget.build(root, "Terminal", "type here", onClose = {}) }
        widget.afterLayout()
        surface.paintTree()

        val glyphBaseline = SpriteGlyphs.requested().size
        val sliceBaseline = slices.variantCount()
        assertTrue(
            glyphBaseline > 0 && sliceBaseline > 0,
            "the initial paint must actually have allocated something, or this test is vacuous"
        )

        val bar = surface.root!!.findById("terminal-scrollbar")
            ?: error("terminal-scrollbar node not found in the built tree")

        val max = widget.pane.maxScroll()
        assertTrue(max > 0, "the scrollback must overflow the viewport, or scrolling is vacuous")

        // TerminalWidget.afterLayout() already stuck the fresh pane to the
        // bottom (see its class KDoc's "stick to bottom" rule), so scrollBy(1)
        // would be a no-op from here. Reset to the top first so the loop below
        // actually exercises every notch on the way back down.
        widget.pane.scrollTo(0)

        var notches = 0
        while (widget.pane.scrollBy(1)) {
            notches++
            surface.paintTree()
            assertEquals(glyphBaseline, SpriteGlyphs.requested().size, "glyphs grew at scroll notch $notches")
            assertEquals(sliceBaseline, slices.variantCount(), "slice variants grew at scroll notch $notches")
        }
        assertTrue(notches > 0, "the pane must actually have scrolled, or the rest of this test is vacuous")
        assertEquals(max, widget.pane.scrollPx, "scrolling must reach the very bottom")

        // Drag the thumb across the WHOLE track, one pixel at a time, both directions.
        val r = bar.rect()
        for (y in r.y..(r.y + r.h)) {
            bar.onGrabMove?.invoke(r.x, y)
            surface.paintTree()
            assertEquals(glyphBaseline, SpriteGlyphs.requested().size, "glyphs grew dragging to y=$y")
            assertEquals(sliceBaseline, slices.variantCount(), "slice variants grew dragging to y=$y")
        }
        for (y in (r.y + r.h) downTo r.y) {
            bar.onGrabMove?.invoke(r.x, y)
            surface.paintTree()
            assertEquals(glyphBaseline, SpriteGlyphs.requested().size, "glyphs grew dragging back to y=$y")
            assertEquals(sliceBaseline, slices.variantCount(), "slice variants grew dragging back to y=$y")
        }

        bar.onGrabMove?.invoke(r.x, r.y + r.h)
        assertEquals(max, widget.pane.scrollPx, "dragging to the track's bottom must reach maxScroll")
        bar.onGrabMove?.invoke(r.x, r.y)
        assertEquals(0, widget.pane.scrollPx, "dragging to the track's top must reach scrollPx 0")
    }

    // Real geometry, from displaykit/slices/manifest.json: gui/widget/scroller
    // is 6x32 with a 1px border on every side.
    private val thumbSprite = SpriteEntry(
        id = SpriteId("gui", "widget/scroller"),
        width = 6, height = 32,
        texture = "minecraft:gui/sprites/widget/scroller.png",
        nineSlice = NineSlice(1, 1, 1, 1)
    )

    private fun thumbHeightFor(trackH: Int, max: Int): Int =
        if (max == 0) trackH else maxOf(32, trackH * trackH / (trackH + max))

    /**
     * A terminal-shaped tree (one scrolling column of rows, no grid) built by
     * hand so [snap] can select between the real, fixed [scrollThumb] (true)
     * and the raw, unsnapped draw [TerminalWidget] used before this fix
     * (false) -- the negative control below needs both from the identical
     * geometry.
     *
     * Wrapped in 20px of top padding so the track's own Y is never inside the
     * canvas's first line -- [io.schemat.displaykit.sprite.GlyphPlacement.resolve]
     * has no earlier row to fall back to there, and a short crop's resolution
     * can legitimately no-op (skip the draw) rather than allocate, which would
     * make this test's growth assertions vacuous right where they matter
     * most. The real [TerminalWidget] never hits this either: its title bar
     * and column padding always sit above the track first.
     */
    private fun buildSyntheticTree(surface: Surface, rowCount: Int, snap: Boolean): Pair<ScrollNode, WidgetNode> {
        val rowH = 10 // TextMetrics.FONT_LINE_HEIGHT_PX
        val viewportH = 100
        val scrollW = 6
        lateinit var pane: ScrollNode
        lateinit var bar: WidgetNode
        surface.layout { root ->
            val column = FlexNode("column", FlexDirection.COLUMN)
            column.padding = PxPadding(top = 20)

            val body = FlexNode("body", FlexDirection.ROW)

            pane = ScrollNode("scroll")
            pane.stepPx = rowH
            pane.height = viewportH
            for (i in 0 until rowCount) {
                val row = WidgetNode("row-$i", PxSize(200, rowH)) { p, r -> p.label("row $i", r.x, r.y) }
                row.height = rowH
                pane.addChild(row)
            }
            body.addChild(pane)

            bar = WidgetNode("bar", PxSize(scrollW, viewportH)) { p, r ->
                val max = pane.maxScroll()
                val thumbH = thumbHeightFor(r.h, max)
                val rawY = if (max == 0) 0 else (pane.scrollPx * (r.h - thumbH)) / max
                val thumbY = r.y + rawY.coerceIn(0, (r.h - thumbH).coerceAtLeast(0))
                if (snap) {
                    p.scrollThumb(Rect(r.x, thumbY, scrollW, thumbH))
                } else {
                    // TerminalWidget.kt's pre-fix render, verbatim: no snap at all.
                    p.frame(thumbSprite, Rect(r.x, thumbY, scrollW, thumbH))
                }
            }
            bar.width = scrollW
            bar.flexGrow = 1
            bar.onGrabMove = { _, y ->
                val r = bar.rect()
                val max = pane.maxScroll()
                val thumbH = thumbHeightFor(r.h, max)
                val span = (r.h - thumbH).coerceAtLeast(1)
                val fraction = ((y - r.y).toDouble() / span).coerceIn(0.0, 1.0)
                pane.scrollTo((fraction * max).toInt())
            }
            body.addChild(bar)

            column.addChild(body)
            root.addChild(column)
        }
        return pane to bar
    }

    /**
     * Isolates why the snap matters, the same way
     * `ScrollbarThumbGlyphStabilityTest`'s negative control does: a full
     * thumb drag across an identical track, with (true) and without (false)
     * the snap. Snapped, a drag must not allocate any new slice variant past
     * the initial paint. Unsnapped, it must.
     */
    @Test
    fun negativeControl_withoutTheSnapADragDriftsOntoNewPhasesAndAllocates() {
        fun variantsAfterFullDrag(snap: Boolean): Pair<Int, Int> {
            SpriteGlyphs.clear()
            slices = FakeSliceSource()
            SliceGlyphSource.installed = slices

            val surface = Surface(220, 100, Vec3d.ZERO, targetWidthBlocks = 3f)
            val (_, bar) = buildSyntheticTree(surface, rowCount = 60, snap = snap)
            surface.paintTree()
            val baseline = slices.variantCount()

            val r = bar.rect()
            for (y in r.y..(r.y + r.h)) {
                bar.onGrabMove?.invoke(r.x, y)
                surface.paintTree()
            }
            return baseline to slices.variantCount()
        }

        val (snappedBaseline, snappedAfter) = variantsAfterFullDrag(snap = true)
        assertTrue(snappedBaseline > 0, "the initial paint must allocate something, or this is vacuous")
        assertEquals(
            snappedBaseline, snappedAfter,
            "snapped: a full drag across the track must not allocate any new slice variant"
        )

        val (unsnappedBaseline, unsnappedAfter) = variantsAfterFullDrag(snap = false)
        assertTrue(
            unsnappedAfter > unsnappedBaseline,
            "negative control: WITHOUT the snap, dragging across the track must drift onto ascent " +
                "phases the initial paint did not cover, and allocate new slices " +
                "(got baseline=$unsnappedBaseline, after=$unsnappedAfter)"
        )
    }
}
