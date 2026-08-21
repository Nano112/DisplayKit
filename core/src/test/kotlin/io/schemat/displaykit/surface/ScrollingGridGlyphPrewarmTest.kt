package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.sprite.GlyphPlacement
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.surface.layout.CrossAxis
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.ScrollNode
import io.schemat.displaykit.surface.layout.WidgetNode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reproduces PickerWindow's grid-inside-a-ScrollNode shape at the primitive
 * level and proves the invariant the tofu bug violated: a sprite's rendered
 * ascent is baked into its font glyph, so painting a row the client has never
 * seen before -- which is exactly what scrolling does, since
 * [ScrollNode.visibleChildren] only emits the rows currently in the viewport
 * -- must not allocate a codepoint the pack does not already carry.
 *
 * PickerWindow itself lives in the `showcase` module and needs a live Fabric
 * server to construct (`ServerPlayer`, `ServerPlayConnectionEvents`), so it
 * cannot be unit tested directly. Everything it actually relies on for this
 * bug -- [Surface.layout]/[Surface.paintTree], [ScrollNode], [WidgetNode],
 * [SurfacePainter.iconFitted], [GlyphPlacement], [SpriteGlyphs] -- lives in
 * `core` and is exercised here instead.
 */
class ScrollingGridGlyphPrewarmTest {

    private val step = 20 // matches PickerWindow.STEP
    private val slot = 18 // matches PickerWindow.SLOT
    private val cols = 4
    private val viewportH = 100 // 5 visible rows of `step`

    private fun entry(i: Int) = SpriteEntry(
        id = SpriteId("items", "sprite$i"),
        width = 16, height = 16,
        texture = "minecraft:item/sprite$i.png"
    )

    @BeforeTest fun reset() = SpriteGlyphs.clear()
    @AfterTest fun tearDown() = SpriteGlyphs.clear()

    /**
     * Builds a picker-shaped tree: a fixed-height [ScrollNode] containing
     * `sprites.size / cols` rows of [step]-tall [FlexNode] rows, each holding
     * up to [cols] [slot]x[slot] cells that draw their sprite fitted into a
     * `(slot - 2)`-square box -- the same shape as PickerWindow's grid pane,
     * down to the `r.x + 1, r.y + 1` inset [Surface.iconFitted] draws at.
     */
    private fun buildGrid(surface: Surface, sprites: List<SpriteEntry>): ScrollNode {
        // This test is entirely about COMPOSITED's glyph-allocation mechanics
        // (SpriteGlyphs growth), which RenderMode.ENTITIES never touches --
        // force COMPOSITED rather than drifting with AUTO's no-SliceGlyphSource
        // default.
        surface.renderMode = RenderMode.COMPOSITED
        lateinit var pane: ScrollNode
        surface.layout { root ->
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
            root.addChild(pane)
        }
        return pane
    }

    /**
     * Mirrors PickerWindow.prewarmGrid: every row shares one ascent phase
     * (step is a multiple of TextMetrics.FONT_LINE_HEIGHT_PX), so reading it
     * off the first real placed cell and allocating one variant per sprite
     * covers the whole list regardless of how many rows scroll past.
     */
    private fun prewarmGrid(sprites: List<SpriteEntry>, pane: ScrollNode) {
        val firstCell = pane.children.first().children.first()
        val y = firstCell.rect().y + 1
        for (e in sprites) {
            val h = e.fitHeight(slot - 2, slot - 2)
            val ascent = GlyphPlacement.resolve(y, h)?.ascent ?: continue
            SpriteGlyphs.request(e, ascent, h)
        }
    }

    @Test
    fun everyGridRowSharesTheSameAscentPhase() {
        // The whole prewarm strategy rests on this: STEP (20) is a multiple
        // of the line pitch (10), so row k's cell sits at the same y mod 10
        // as row 0's, however many rows separate them.
        val sprites = (0 until 40).map(::entry)
        val surface = Surface(200, 100, Vec3d.ZERO, targetWidthBlocks = 3f)
        val pane = buildGrid(surface, sprites)

        val phases = pane.children.map { row ->
            (row.children.first().rect().y + 1) % 10
        }.toSet()
        assertEquals(1, phases.size, "every row must land on the same ascent phase, got $phases")
    }

    @Test
    fun scrollingRevealsRowsWithoutGrowingGlyphsAfterPrewarm() {
        val sprites = (0 until 40).map(::entry) // 10 rows of 4, 5 visible at a time
        val surface = Surface(200, 100, Vec3d.ZERO, targetWidthBlocks = 3f)
        val pane = buildGrid(surface, sprites)

        prewarmGrid(sprites, pane)
        val baseline = SpriteGlyphs.requested().size
        assertEquals(sprites.size, baseline, "one variant per sprite from the prewarm")

        surface.paintTree() // paints only the initial 5 visible rows
        assertEquals(baseline, SpriteGlyphs.requested().size, "the initial page must not grow past the prewarm")

        // Scroll far enough that rows 5-9 -- never painted before -- enter
        // the viewport. Without the prewarm this is exactly what used to
        // allocate fresh codepoints the client's pack did not have: tofu.
        assertEquals(100, pane.maxScroll())
        repeat(5) { assertTrue(pane.scrollBy(1)) }
        surface.paintTree()
        assertEquals(
            baseline, SpriteGlyphs.requested().size,
            "scrolled-in rows must reuse pre-warmed variants, not allocate new ones"
        )
    }

    @Test
    fun withoutPrewarmScrollingDoesAllocateNewGlyphs() {
        // Proves the invariant above is not vacuous: the same scroll, on the
        // same tree, WITHOUT the prewarm step, does grow the glyph table --
        // this is the bug repaintTree used to have before it gained a growth
        // check and PickerWindow.repaint gained a prewarm.
        val sprites = (0 until 40).map(::entry)
        val surface = Surface(200, 100, Vec3d.ZERO, targetWidthBlocks = 3f)
        val pane = buildGrid(surface, sprites)

        surface.paintTree()
        val afterFirstPage = SpriteGlyphs.requested().size
        assertEquals(20, afterFirstPage, "5 visible rows of 4 sprites, one variant each")

        repeat(5) { pane.scrollBy(1) }
        surface.paintTree()
        assertTrue(
            SpriteGlyphs.requested().size > afterFirstPage,
            "scrolling to never-before-seen rows must allocate new glyphs when nothing pre-warmed them"
        )
    }
}
