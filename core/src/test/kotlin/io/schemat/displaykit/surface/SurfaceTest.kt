package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.Billboard
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextAlignment
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import org.joml.Matrix4f
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SurfaceTest {

    private fun surface(w: Int = 200, h: Int = 120) =
        Surface(widthPx = w, heightPx = h, position = Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 2f)

    // A canvas that requires no pack at all to render (font = null throughout) is
    // exactly what the fallback in toEntity() must never discard, so these tests
    // need SliceGlyphSource.installed to genuinely be null and stay that way.
    @BeforeTest fun clearSliceSource() { SliceGlyphSource.installed = null }
    @AfterTest fun restoreSliceSource() { SliceGlyphSource.installed = null }

    @Test
    fun pixelScaleIsDerivedFromTheTargetWidth() {
        val s = surface(w = 400)
        // 2 blocks / (400 px * 0.025) = 0.2
        assertTrue(abs(0.2f - s.pixelScale) < 1e-5f, "got ${s.pixelScale}")
    }

    @Test
    fun anEmptySurfaceStillProducesExactlyOneEntity() {
        val e = surface().toEntity()
        assertEquals(Vec3d(0.0, 70.0, 0.0), e.position)
    }

    @Test
    fun fillTilesEnoughToCoverTheRect() {
        val s = surface()
        s.paint { fill(DkColor.fromRGB(0, 255, 136), Rect(0, 0, 32, 32)) }
        // the fill sprite is 16x16, so a 32x32 rect needs 4 tiles
        assertEquals(4, s.canvasItemCount())
    }

    @Test
    fun labelAndIconBothLandOnTheCanvas() {
        val s = surface()
        s.paint { label("hello", 4, 0, DkColor.WHITE) }
        assertEquals(1, s.canvasItemCount())
    }

    @Test
    fun regionRegistersAHitRect() {
        val s = surface()
        var fired = 0
        s.paint { region("close", Rect(10, 10, 14, 14)) { fired++ } }
        val rects = s.hitRects()
        assertEquals(1, rects.size)
        assertEquals("close", rects[0].id)
        rects[0].onClick()
        assertEquals(1, fired)
    }

    @Test
    fun hitRectsAreInDrawOrderSoLaterOnesSitOnTop() {
        val s = surface()
        s.paint {
            region("under", Rect(0, 0, 50, 50)) {}
            region("over", Rect(10, 10, 10, 10)) {}
        }
        assertEquals(listOf("under", "over"), s.hitRects().map { it.id })
    }

    @Test
    fun repaintingReplacesRatherThanAccumulates() {
        val s = surface()
        s.paint { region("a", Rect(0, 0, 4, 4)) {} }
        s.paint { region("b", Rect(0, 0, 4, 4)) {} }
        assertEquals(listOf("b"), s.hitRects().map { it.id })
    }

    @Test
    fun slotsRecordTheirItemsForOverlayRendering() {
        val s = surface()
        s.paint { slot(8, 8, ItemRef("minecraft:diamond")) }
        val items = s.slotItems()
        assertEquals(1, items.size)
        assertEquals("minecraft:diamond", items[0].second.itemId)
    }

    @Test
    fun aRegionOutsideTheSurfaceIsRejected() {
        val s = surface(w = 100, h = 100)
        assertFailsWith<IllegalArgumentException> {
            s.paint { region("oops", Rect(90, 90, 40, 40)) {} }
        }
    }

    @Test
    fun interactiveSurfacesMustNotBillboard() {
        val e = assertFailsWith<IllegalArgumentException> {
            Surface(100, 100, Vec3d.ZERO, 2f, orientation = Billboard.CENTER)
                .paint { region("x", Rect(0, 0, 4, 4)) {} }
        }
        assertTrue(e.message!!.contains("FIXED"))
    }

    // --- Finding 1: the pack-disabled fallback must not discard real text ---

    @Test
    fun textOnlyCanvasWithNoSourceInstalledRendersRealText() {
        val s = surface()
        s.paint { label("hello", 0, 0, DkColor.WHITE) }
        assertEquals("hello", s.toEntity().text.plain())
    }

    @Test
    fun canvasWithASpriteDrawAndNoSourceInstalledRendersTheFallback() {
        val s = surface()
        val entry = SpriteEntry(
            id = SpriteId("gui", "test_icon"),
            width = 8, height = 8,
            texture = "minecraft:gui/test_icon.png",
            greyscale = true
        )
        s.paint { icon(entry, 0, 0) }
        assertEquals(
            "[DisplayKit surface unavailable: resource pack disabled]",
            s.toEntity().text.plain()
        )
    }

    // --- Finding 2: pixelScale's inputs must be guarded at construction ---

    @Test
    fun widthPxMustBePositive() {
        val e = assertFailsWith<IllegalArgumentException> {
            Surface(0, 100, Vec3d.ZERO, 2f)
        }
        assertTrue(e.message!!.contains("widthPx"))
    }

    @Test
    fun heightPxMustBePositive() {
        val e = assertFailsWith<IllegalArgumentException> {
            Surface(100, 0, Vec3d.ZERO, 2f)
        }
        assertTrue(e.message!!.contains("heightPx"))
    }

    @Test
    fun targetWidthBlocksMustBePositive() {
        val e = assertFailsWith<IllegalArgumentException> {
            Surface(100, 100, Vec3d.ZERO, -1f)
        }
        assertTrue(e.message!!.contains("targetWidthBlocks"))
    }

    // --- Finding 3: fill must not overdraw past its rect ---

    @Test
    fun fillDoesNotPaintOutsideARectThatIsNotAWholeMultipleOfTheTile() {
        val s = surface()
        s.paint { fill(DkColor.fromRGB(255, 0, 0), Rect(0, 0, 20, 20)) }
        // the fill sprite (lightning_rod_on) is 16x16 — every tile must stay
        // fully inside [0,20) x [0,20), never bleeding past the rect's edge.
        for ((x, y) in s.canvasItemPositions()) {
            assertTrue(x >= 0 && x + 16 <= 20, "tile at x=$x overflows the rect")
            assertTrue(y >= 0 && y + 16 <= 20, "tile at y=$y overflows the rect")
        }
    }

    @Test
    fun fillRectSmallerThanTheTileThrows() {
        val s = surface()
        val e = assertFailsWith<IllegalArgumentException> {
            s.paint { fill(DkColor.WHITE, Rect(0, 0, 10, 20)) }
        }
        assertTrue(e.message!!.contains("16"))
    }

    // --- Layout fixes: rows must not wrap, and must not be centred independently ---

    @Test
    fun toEntityAlignsTextLeftSoEveryRowSharesTheSameOrigin() {
        // VirtualTextDisplay defaults to CENTER, which centres each line of
        // the text component independently — rows of different widths then
        // slide sideways relative to each other. The canvas model assumes
        // every row starts at x=0, which only LEFT alignment preserves.
        val s = surface()
        s.paint { label("hi", 0, 0, DkColor.WHITE) }
        assertEquals(TextAlignment.LEFT, s.toEntity().textAlignment)
    }

    @Test
    fun toEntityDerivesLineWidthFromTheWidestRowSoItCannotWrap() {
        // VirtualTextDisplay defaults lineWidth to 200; the client wraps any
        // row wider than that. A row of real content past 200px must push
        // lineWidth past both 200 and its own true width, or wrapping is
        // still possible.
        val s = surface(w = 600, h = 60)
        val longLabel = "a label wide enough to guarantee wrapping under the two " +
            "hundred pixel default line width if lineWidth were left untouched"
        s.paint { label(longLabel, 0, 0, DkColor.WHITE) }

        val lineWidth = s.toEntity().lineWidth
        assertTrue(lineWidth > 200, "expected lineWidth > 200, got $lineWidth")
        assertTrue(
            lineWidth >= TextMetrics.textWidthPx(longLabel),
            "lineWidth ($lineWidth) must cover the row's true width " +
                "(${TextMetrics.textWidthPx(longLabel)})"
        )
    }

    // --- Feature: backdrop ---

    @Test
    fun backdropDefaultsToTransparent() {
        val s = surface()
        assertEquals(DkColor.TRANSPARENT, s.toEntity().backgroundColor)
    }

    @Test
    fun settingBackdropBecomesTheEntitysBackgroundColor() {
        val s = surface()
        s.backdrop = DkColor(255, 10, 20, 30)
        assertEquals(DkColor(255, 10, 20, 30), s.toEntity().backgroundColor)
    }

    // --- Feature: yawDegrees ---

    /**
     * Float-approximate matrix comparison. `Mat4f` (and JOML's `Matrix4f`)
     * compares bit-exact, and `rotateY` can legitimately produce `-0.0f`
     * where a hand-built matrix has `0.0f` for the same mathematically-zero
     * cell (e.g. `-sin(0f)`) -- equal in value, distinct in bit pattern.
     */
    private fun assertMatrixApproxEquals(expected: Mat4f, actual: Mat4f, epsilon: Float = 1e-5f) {
        val e = expected.toFloatArray()
        val a = actual.toFloatArray()
        for (i in e.indices) {
            assertTrue(abs(e[i] - a[i]) < epsilon, "matrix element $i differs: expected ${e[i]}, got ${a[i]}")
        }
    }

    @Test
    fun toEntityAppliesNoRotationWhenYawIsZero() {
        val s = surface()
        val scale = s.pixelScale
        val expected = Mat4f(Matrix4f().scale(scale, scale, scale))
        assertMatrixApproxEquals(expected, s.toEntity().transformation)
    }

    @Test
    fun toEntityComposesTheYawRotationWithTheExistingScale() {
        val s = surface()
        s.yawDegrees = 90f
        val scale = s.pixelScale
        val expected = Mat4f(Matrix4f().rotateY(Math.toRadians(90.0).toFloat()).scale(scale, scale, scale))
        assertMatrixApproxEquals(expected, s.toEntity().transformation)
    }
}
