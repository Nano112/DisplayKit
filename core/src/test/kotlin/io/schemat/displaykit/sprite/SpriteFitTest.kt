package io.schemat.displaykit.sprite

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.SliceGlyphSource
import io.schemat.displaykit.surface.Surface
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fitted sprite's placement, shared by the painter and by pre-warming.
 *
 * These two computing placement separately is not a hypothetical: they did,
 * and the pre-warm used the cell's top-left while `iconFitted` centred the
 * sprite in it. Square icons fill their box and centre to zero, so item and
 * block atlases looked fine; every WIDE sprite landed on a different y, took
 * a different glyph ascent, and was therefore a variant nothing had warmed.
 * The `gui` atlas is all panels, so scrolling that tab allocated codepoints
 * and re-downloaded the resource pack -- the exact thing warming exists to
 * prevent, failing only on one tab.
 */
class SpriteFitTest {

    private fun entry(w: Int, h: Int) = SpriteEntry(
        id = SpriteId("gui", "test_${w}x$h"), width = w, height = h,
        texture = "minecraft:gui/test.png"
    )

    @BeforeTest fun setUp() { SliceGlyphSource.installed = null }
    @AfterTest fun tearDown() { SliceGlyphSource.installed = null }

    @Test
    fun aSquareSpriteFillsItsBoxAndCentresToZero() {
        val e = entry(16, 16)
        val (x, y) = SpriteFit.origin(e, 100, 200, 16, 16)
        assertEquals(100, x)
        assertEquals(200, y, "a square sprite must not be nudged; this is the case that hid the bug")
    }

    @Test
    fun aWideSpriteIsPushedDownByHalfTheLeftoverHeight() {
        // A 130x24 panel fitted into 16x16 keeps its aspect, so it is much
        // shorter than the box and centres well below the box's top edge.
        val e = entry(130, 24)
        val h = SpriteFit.height(e, 16, 16)
        val (_, y) = SpriteFit.origin(e, 0, 0, 16, 16)

        assertTrue(h < 16, "a wide sprite must fit to less than the box height, got $h")
        assertEquals((16 - h) / 2, y, "it must be centred, not top-aligned")
        assertTrue(y > 0, "and therefore NOT at the box's top-left, which is what pre-warming assumed")
    }

    @Test
    fun theOriginIsCentredHorizontallyToo() {
        val e = entry(8, 16)
        val h = SpriteFit.height(e, 16, 16)
        val w = e.scaledWidth(h)
        val (x, _) = SpriteFit.origin(e, 0, 0, 16, 16)
        assertEquals((16 - w) / 2, x)
    }

    @Test
    fun thePainterDrawsExactlyWhereSpriteFitSays() {
        // The invariant that stops the two drifting again. Uses ENTITIES so
        // the painted rect is inspectable directly.
        val s = Surface(200, 120, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 2f)
            .apply { renderMode = RenderMode.ENTITIES }
        val e = entry(130, 24)
        val boxX = 30
        val boxY = 40
        val box = 16

        s.paint { iconFitted(e, boxX, boxY, box, box) }
        val rect = s.paintedSpriteRectsForTest().single()
        val (wantX, wantY) = SpriteFit.origin(e, boxX, boxY, box, box)

        assertEquals(wantX, rect.x, "painter and SpriteFit must agree on x")
        assertEquals(wantY, rect.y, "painter and SpriteFit must agree on y -- the pre-warm trusts this")
        assertEquals(SpriteFit.height(e, box, box), rect.h, "and on height")
    }

    @Test
    fun everyBundledGuiSpriteCentresConsistently() {
        // Sweep the real atlas that broke: whatever each panel's aspect, the
        // painter's y and SpriteFit's y must match, or that sprite is a
        // variant pre-warming will miss.
        val box = 16
        val gui = SpriteIndex.bundled.all()
            .filter { it.id.atlas == "gui" && it.glyphEligible }
            .take(60)
        assertTrue(gui.isNotEmpty(), "the bundled index must carry gui sprites")

        for (e in gui) {
            val s = Surface(200, 120, Vec3d.ZERO, targetWidthBlocks = 2f)
                .apply { renderMode = RenderMode.ENTITIES }
            s.paint { iconFitted(e, 10, 20, box, box) }
            val rect = s.paintedSpriteRectsForTest().single()
            val (wx, wy) = SpriteFit.origin(e, 10, 20, box, box)
            assertEquals(wx to wy, rect.x to rect.y, "placement disagrees for ${e.id}")
        }
    }
}
