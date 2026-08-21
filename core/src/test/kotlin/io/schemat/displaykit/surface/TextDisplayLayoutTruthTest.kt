package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the three client-layout facts that four rounds of visual bugs traced
 * back to. Each of these was, at some point, assumed rather than measured.
 *
 * See `docs/superpowers/specs/2026-08-21-text-display-layout-truth.md`.
 */
class TextDisplayLayoutTruthTest {

    private class NoSlices : SliceGlyphSource {
        override fun request(id: SpriteId) {}
        override fun codepointFor(id: SpriteId, srcX: Int, srcY: Int, ascent: Int) = 1
        override fun advanceFor(id: SpriteId, srcX: Int, srcY: Int): Int? = null
    }

    // entityOrigin()'s compensation only applies on the non-degraded path.
    @BeforeTest fun install() { SliceGlyphSource.installed = NoSlices() }
    @AfterTest fun clear() { SliceGlyphSource.installed = null }

    private fun surface(w: Int = 320, h: Int = 200) =
        Surface(w, h, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 4f)

    // --- Fact 1: the advance is the TRIMMED width plus one ---

    @Test
    fun glyphAdvanceComesFromTheTrimmedWidthNotTheDeclaredOne() {
        val padded = SpriteEntry(
            id = SpriteId("gui", "padded"),
            width = 16, height = 16, texture = "minecraft:padded.png",
            trimmedWidth = 14
        )
        assertEquals(15, padded.glyphAdvance, "advance is trimmedWidth + 1")
        assertEquals(17, padded.copy(trimmedWidth = 16).glyphAdvance)
    }

    @Test
    fun trimmedWidthDefaultsToTheDeclaredWidthWhenUnmeasured() {
        val e = SpriteEntry(
            id = SpriteId("gui", "plain"),
            width = 12, height = 12, texture = "minecraft:plain.png"
        )
        assertEquals(12, e.trimmedWidth)
        assertEquals(13, e.glyphAdvance)
    }

    // --- Fact 2: the renderer's line pitch is 10, not Font.lineHeight (9) ---

    @Test
    fun theRendererLinePitchIsTen() {
        assertEquals(
            10, TextMetrics.FONT_LINE_HEIGHT_PX,
            "DisplayRenderer\$TextDisplayRenderer computes 9 + 1; using Font's " +
                "own lineHeight of 9 drifts one pixel per row"
        )
    }

    // --- Fact 3: position is the canvas top-left, compensating for centring ---

    @Test
    fun everyCanvasCornerRoundTripsThroughPicking() {
        // If entityOrigin() and SurfacePicking disagree about where the canvas
        // sits, a window renders in one place and takes clicks in another.
        // Walking real corners through the ray maths catches that.
        val s = surface()
        s.paint { label("round trip", 0, 0, DkColor.WHITE) }

        val unit = (s.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        val straightAhead = Vec3d(0.0, 0.0, 1.0)
        for ((px, py) in listOf(0 to 0, 1 to 1, 100 to 50, s.widthPx - 1 to s.heightPx - 1)) {
            // Centre of pixel (px, py), per SurfacePicking's own mapping.
            val world = Vec3d(
                s.position.x + (px + 0.5) * unit,
                s.position.y - (py + 0.5) * unit,
                s.position.z - 2.0
            )
            val got = SurfacePicking.localPixel(s, world, straightAhead)
            assertNotNull(got, "pixel ($px,$py) should be pickable")
            assertEquals(px to py, got, "pixel ($px,$py) round trip")
        }
    }

    @Test
    fun theEntitySitsAtTheBlockCentreNotTheCanvasTopLeft() {
        val s = surface()
        s.paint { label("x", 0, 0, DkColor.WHITE) }
        val origin = s.entityOrigin()

        // The client centres the block horizontally on the entity and hangs it
        // upward from it, so the entity must sit right of, and below, the
        // canvas top-left that `position` names.
        assertTrue(origin.x > s.position.x, "entity is right of the canvas origin, got $origin")
        assertTrue(origin.y < s.position.y, "entity is below the canvas origin, got $origin")
    }

    @Test
    fun anchoringFixesTheBlockWidthSoContentCannotShiftTheSurface() {
        // A narrow widget and a wide one must produce the same block width, or
        // the surface slides sideways whenever its content changes.
        val a = surface().apply { paint { label("i", 0, 0, DkColor.WHITE) } }
        val b = surface().apply { paint { label("a much longer label", 0, 0, DkColor.WHITE) } }
        assertEquals(a.entityOrigin(), b.entityOrigin())
    }
}
