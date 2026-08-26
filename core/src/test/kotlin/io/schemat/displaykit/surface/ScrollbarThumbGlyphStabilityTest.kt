package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.GlyphPlacement
import io.schemat.displaykit.sprite.NineSlice
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.surface.layout.CrossAxis
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.PxPadding
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.ScrollNode
import io.schemat.displaykit.surface.layout.WidgetNode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Extends [ScrollingGridGlyphPrewarmTest]'s invariant to the whole picker
 * window shape: a scrolling grid PLUS a draggable scrollbar thumb, together,
 * across a full scroll through every page and a full thumb drag across the
 * track.
 *
 * `PickerWindow.kt`'s scrollbar thumb render used to compute `thumbY` as an
 * arbitrary function of `scrollPx`, landing the thumb's nine-slice crops on a
 * new font-ascent phase (`y mod TextMetrics.FONT_LINE_HEIGHT_PX`) on almost
 * every notch — each one a candidate for a brand-new codepoint and (via
 * `PickerWindow.repaintTree`'s growth check) a full client pack resend mid-
 * scroll. `PickerWindow` itself needs a live Fabric server and cannot be unit
 * tested (see `ScrollingGridGlyphPrewarmTest`'s KDoc), so this reproduces its
 * grid+thumb shape at the primitive level instead, the same way that test
 * does for the grid alone.
 */
class ScrollbarThumbGlyphStabilityTest {

    private val step = 20 // matches PickerWindow.STEP
    private val slot = 18 // matches PickerWindow.SLOT
    private val scrollW = 6 // matches PickerWindow.SCROLL_W
    private val cols = 4
    private val viewportH = 100 // 5 visible rows of `step`

    // Real geometry, from displaykit/slices/manifest.json: gui/widget/scroller
    // is 6x32 with a 1px border on every side. Width/height drive
    // NineSliceLayout.regionsFor; nineSlice must be non-null or NineSlicePainter
    // draws (and prewarms) nothing at all.
    private val thumbSprite = SpriteEntry(
        id = SpriteId("gui", "widget/scroller"),
        width = 6, height = 32,
        texture = "minecraft:gui/sprites/widget/scroller.png",
        nineSlice = NineSlice(1, 1, 1, 1)
    )

    private fun entry(i: Int) = SpriteEntry(
        id = SpriteId("items", "sprite$i"),
        width = 16, height = 16,
        texture = "minecraft:item/sprite$i.png"
    )

    /** One crop of the thumb sprite at one ascent -- the slice variant key. */
    private data class SliceAsk(val srcX: Int, val srcY: Int, val ascent: Int)

    /**
     * Counts distinct (crop, ascent) variants the same way the real
     * `SpriteSliceProvider.variantCount()` does (see that class in the `pack`
     * module) -- `core` cannot depend on `pack`, so this stands in for it via
     * the same [SliceGlyphSource] seam the platform layer installs.
     */
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
        SpriteGlyphs.clear()
        slices = FakeSliceSource()
        SliceGlyphSource.installed = slices
    }

    @AfterTest
    fun tearDown() {
        SpriteGlyphs.clear()
        SliceGlyphSource.installed = null
    }

    /** PickerWindow.thumbHeightFor, verbatim. */
    private fun thumbHeightFor(trackH: Int, max: Int): Int =
        if (max == 0) trackH else maxOf(32, trackH * trackH / (trackH + max))

    /** PickerWindow.repaint's prewarmGrid, verbatim. */
    private fun prewarmGrid(sprites: List<SpriteEntry>, pane: ScrollNode) {
        val firstCell = pane.children.first().children.first()
        val y = firstCell.rect().y + 1
        for (e in sprites) {
            val h = e.fitHeight(slot - 2, slot - 2)
            val ascent = GlyphPlacement.resolve(y, h)?.ascent ?: continue
            SpriteGlyphs.request(e, ascent, h)
        }
    }

    /**
     * PickerWindow.prewarmScrollThumb, verbatim (minus the SpriteIndex.bundled
     * lookup): probes one line-pitch ABOVE the snap's real minimum Y
     * (`bar.rect().y`), not a synthetic `0 until 10` sweep and not
     * `bar.rect().y` directly either -- see that function's KDoc for why
     * both of those are wrong when the track can start within the first
     * line (as it deliberately does in this test's tree, to exercise
     * exactly that edge).
     */
    private fun prewarmScrollThumb(
        bar: WidgetNode,
        pane: ScrollNode,
        additionalMaxScrolls: List<Int> = emptyList()
    ) {
        val r = bar.rect()
        if (r.h <= 0) return
        val probeY = snapToLinePitch(r.y) + TextMetrics.FONT_LINE_HEIGHT_PX
        for (maxScroll in (additionalMaxScrolls + pane.maxScroll()).distinct()) {
            val thumbH = thumbHeightFor(r.h, maxScroll)
            NineSlicePainter.prewarm(thumbSprite, Rect(0, probeY, scrollW, thumbH))
        }
    }

    /**
     * Builds a grid+scrollbar tree shaped like `PickerWindow.repaint`'s
     * `body` row: the pane and the scrollbar are both top-aligned ROW
     * children starting at the same Y, exactly as `pane` and `barWrap` do
     * there -- so this reproduces whatever relationship (or lack of one)
     * exists in production between the track's own Y and the grid's ascent
     * phase, rather than assuming they agree.
     *
     * [snap] selects `PickerWindow.kt`'s fixed render (true) or the
     * pre-fix arbitrary one (false, the negative control).
     */
    private fun buildTree(
        surface: Surface,
        sprites: List<SpriteEntry>,
        snap: Boolean,
        topOffset: Int = 0
    ): Pair<ScrollNode, WidgetNode> {
        lateinit var pane: ScrollNode
        lateinit var bar: WidgetNode
        surface.layout { root ->
            val body = FlexNode("body", FlexDirection.ROW)
            body.padding = PxPadding(top = topOffset)

            pane = ScrollNode("grid")
            pane.stepPx = step
            pane.height = viewportH
            for (rowStart in sprites.indices step cols) {
                val row = FlexNode(
                    "row-$rowStart", FlexDirection.ROW,
                    crossAxis = CrossAxis.CENTER
                )
                row.height = step
                for (i in rowStart until minOf(rowStart + cols, sprites.size)) {
                    val e = sprites[i]
                    val cell = WidgetNode("cell-$i", PxSize(slot, slot)) { p, r ->
                        p.iconFitted(e, r.x + 1, r.y + 1, slot - 2, slot - 2)
                    }
                    row.addChild(cell)
                }
                pane.addChild(row)
            }
            body.addChild(pane)

            bar = WidgetNode("scrollbar", PxSize(scrollW, viewportH)) { p, r ->
                val max = pane.maxScroll()
                val thumbH = thumbHeightFor(r.h, max)
                val thumbY = if (snap) {
                    val rawY = if (max == 0) 0 else (pane.scrollPx * (r.h - thumbH)) / max
                    // scrollThumb snaps the final absolute canvas Y.
                    snapToLinePitch(r.y + rawY)
                } else {
                    // The pre-fix arbitrary render.
                    if (max == 0) r.y else r.y + (pane.scrollPx * (r.h - thumbH)) / max
                }
                p.frame(thumbSprite, Rect(r.x, thumbY, scrollW, thumbH))
            }
            body.addChild(bar)

            root.addChild(body)
        }
        return pane to bar
    }

    /** PickerWindow's onGrabMove logic, verbatim. */
    private fun dragTo(bar: WidgetNode, pane: ScrollNode, y: Int): Boolean {
        val r = bar.rect()
        val max = pane.maxScroll()
        val thumbH = thumbHeightFor(r.h, max)
        val span = (r.h - thumbH).coerceAtLeast(1)
        val fraction = ((y - r.y).toDouble() / span).coerceIn(0.0, 1.0)
        return pane.scrollTo((fraction * max).toInt())
    }

    @Test
    fun fullScrollThenFullDragAllocatesNoNewGlyphsOrSlices() {
        val sprites = (0 until 40).map(::entry) // 10 rows of 4, 5 visible at a time
        val surface = Surface(220, 100, Vec3d.ZERO, targetWidthBlocks = 3f)
        val (pane, bar) = buildTree(surface, sprites, snap = true)

        // The track's own Y need not share the grid's ascent phase for this
        // to hold -- prewarmScrollThumb covers all 10 residues regardless.
        // Recorded here so a future change to this test's geometry can see at
        // a glance whether that coincidentally started matching.
        val gridPhase = (pane.children.first().children.first().rect().y + 1) % 10
        val trackPhase = bar.rect().y % 10

        prewarmGrid(sprites, pane)
        prewarmScrollThumb(bar, pane)
        surface.paintTree()

        val glyphBaseline = SpriteGlyphs.requested().size
        val sliceBaseline = slices.variantCount()
        assertTrue(
            glyphBaseline > 0 && sliceBaseline > 0,
            "prewarm must actually have allocated something, or this test is vacuous"
        )

        val max = pane.maxScroll()
        assertEquals(100, max)

        // Scroll through EVERY page, one notch at a time.
        var notches = 0
        while (pane.scrollBy(1)) {
            notches++
            surface.paintTree()
            assertEquals(glyphBaseline, SpriteGlyphs.requested().size, "glyphs grew at scroll notch $notches (grid phase=$gridPhase, track phase=$trackPhase)")
            assertEquals(sliceBaseline, slices.variantCount(), "slice variants grew at scroll notch $notches (grid phase=$gridPhase, track phase=$trackPhase)")
        }
        assertTrue(notches > 0, "the grid must actually have scrolled, or the rest of this test is vacuous")
        assertEquals(max, pane.scrollPx, "scrolling must reach the very bottom")

        // Drag the thumb across the WHOLE track, one pixel at a time, both directions.
        val r = bar.rect()
        for (y in r.y..(r.y + r.h)) {
            dragTo(bar, pane, y)
            surface.paintTree()
            assertEquals(glyphBaseline, SpriteGlyphs.requested().size, "glyphs grew dragging to y=$y")
            assertEquals(sliceBaseline, slices.variantCount(), "slice variants grew dragging to y=$y")
        }
        for (y in (r.y + r.h) downTo r.y) {
            dragTo(bar, pane, y)
            surface.paintTree()
            assertEquals(glyphBaseline, SpriteGlyphs.requested().size, "glyphs grew dragging back to y=$y")
            assertEquals(sliceBaseline, slices.variantCount(), "slice variants grew dragging back to y=$y")
        }

        // The drag mapping (onGrabMove) is untouched by the render-side snap
        // -- confirm it still reaches both ends of the scroll range.
        assertTrue(dragTo(bar, pane, r.y + r.h))
        assertEquals(max, pane.scrollPx, "dragging to the track's bottom must reach maxScroll")
        assertTrue(dragTo(bar, pane, r.y))
        assertEquals(0, pane.scrollPx, "dragging to the track's top must reach scrollPx 0")
    }

    @Test
    fun nonAlignedTrackPrewarmsTheAbsoluteSnappedPhase() {
        val sprites = (0 until 40).map(::entry)
        val surface = Surface(220, 120, Vec3d.ZERO, targetWidthBlocks = 3f)
        val (pane, bar) = buildTree(surface, sprites, snap = true, topOffset = 7)

        assertEquals(7, bar.rect().y % TextMetrics.FONT_LINE_HEIGHT_PX)
        prewarmScrollThumb(bar, pane)
        surface.paintTree()
        val baseline = slices.variantCount()

        while (pane.scrollBy(1)) {
            surface.paintTree()
            assertEquals(baseline, slices.variantCount())
        }
    }

    @Test
    fun atlasSwitchPrewarmsEveryPossibleThumbHeight() {
        val initial = (0 until 40).map(::entry)
        val larger = (0 until 100).map(::entry)
        val surface = Surface(220, 120, Vec3d.ZERO, targetWidthBlocks = 3f)
        val (pane, bar) = buildTree(surface, initial, snap = true, topOffset = 7)
        val largerMaxScroll = (larger.size / cols) * step - viewportH

        prewarmScrollThumb(bar, pane, additionalMaxScrolls = listOf(largerMaxScroll))
        surface.paintTree()
        val baseline = slices.variantCount()

        buildTree(surface, larger, snap = true, topOffset = 7)
        surface.paintTree()
        assertEquals(
            baseline,
            slices.variantCount(),
            "switching to an atlas with a different row count must not mint a new thumb height"
        )
    }

    /**
     * Isolates WHY the snap matters, using exactly [prewarmScrollThumb]'s own
     * (single-probe) strategy: prewarm ONLY the thumb's real minimum Y, then
     * show that with the snap, a full drag on that one prewarm allocates
     * nothing new; WITHOUT the snap, the identical drag on the identical
     * prewarm does allocate new slices. This is the non-vacuous negative
     * control -- it is the snap itself keeping the thumb on one phase (and
     * at that, always the SAME resolved ascent -- see [prewarmScrollThumb]'s
     * KDoc on why `y mod 10` alone is not quite the right invariant), not
     * some other property of the prewarm or the tree shape.
     */
    @Test
    fun negativeControl_withoutTheSnapASinglePrewarmedPhaseIsNotEnough() {
        // Deliberately NOT the 40-sprite grid the other tests use: with that
        // grid's geometry (thumbH=50, max=100, stepPx=20), the unsnapped
        // render's thumbY happens to land on an exact multiple of 10 at
        // every reachable scrollPx anyway (a coincidence of the 1:2 ratio
        // between (r.h-thumbH) and max) -- which would make the "unsnapped"
        // arm of this control vacuously pass for the wrong reason. 100
        // sprites (25 rows) gives max=400 and thumbH clamped to its 32px
        // floor, a ratio that actually drifts thumbY off multiples of 10 as
        // scrollPx steps through its stepPx-quantized values.
        val sprites = (0 until 100).map(::entry)

        fun variantsAllocatedDraggingWholeTrack(snap: Boolean): Pair<Int, Int> {
            SpriteGlyphs.clear()
            slices = FakeSliceSource()
            SliceGlyphSource.installed = slices

            val surface = Surface(220, 100, Vec3d.ZERO, targetWidthBlocks = 3f)
            val (pane, bar) = buildTree(surface, sprites, snap = snap)
            prewarmScrollThumb(bar, pane)
            val baseline = slices.variantCount()

            val r = bar.rect()
            for (y in r.y..(r.y + r.h)) {
                dragTo(bar, pane, y)
                surface.paintTree()
            }
            return baseline to slices.variantCount()
        }

        val (snappedBaseline, snappedAfter) = variantsAllocatedDraggingWholeTrack(snap = true)
        assertTrue(snappedBaseline > 0, "the single-phase prewarm must allocate something, or this is vacuous")
        assertEquals(
            snappedBaseline, snappedAfter,
            "snapped: the thumb must stay on the one pre-warmed phase for the whole drag"
        )

        val (unsnappedBaseline, unsnappedAfter) = variantsAllocatedDraggingWholeTrack(snap = false)
        assertTrue(
            unsnappedAfter > unsnappedBaseline,
            "negative control: WITHOUT the snap, dragging across the track must drift onto " +
                "ascent phases the single-phase prewarm did not cover, and allocate new slices " +
                "(got baseline=$unsnappedBaseline, after=$unsnappedAfter)"
        )
    }
}
