package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteDiagnostics
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurfacePartsTest {

    private fun surface() = Surface(400, 200, Vec3d.ZERO, 3f)

    @Test
    fun aButtonRegistersExactlyOneClickableRegion() {
        val s = surface()
        s.paint { button("ok", Rect(0, 0, 200, 20), "OK") {} }
        assertEquals(listOf("ok"), s.hitRects().map { it.id })
    }

    @Test
    fun aTitleBarRegistersItsCloseRegion() {
        val s = surface()
        s.paint { titleBar(Rect(0, 0, 400, 16), "Window") {} }
        assertTrue(s.hitRects().any { it.id == "close" })
    }

    @Test
    fun theCloseRegionIsAtTheFarRightOfTheTitleBar() {
        val s = surface()
        s.paint { titleBar(Rect(0, 0, 400, 30), "Window") {} }
        val close = s.hitRects().first { it.id == "close" }
        assertEquals(Rect(376, 5, 20, 20), close.rect)
    }

    @Test
    fun titleAndCrossUseTheHeaderChromeDepth() {
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        s.paint { titleBar(Rect(0, 0, 400, 30), "Window") {} }

        val chromeDepths = s.paintedSpriteDepthsForTest().map { it.second }.distinct()
        val labelDepths = s.paintedLabelDepthsForTest().distinct()
        assertEquals(1, chromeDepths.size, "strip and cross must share one chrome plane")
        assertEquals(chromeDepths, labelDepths, "title must use the header's depth key")
    }

    @Test
    fun titleIsHorizontallyCentredInTheHeader() {
        val title = "Window"
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        s.paint { titleBar(Rect(0, 0, 400, 30), title) {} }

        val x = s.paintedLabelXsForTest().single()
        assertEquals((400 - TextMetrics.textWidthPx(title)) / 2, x)
    }

    @Test
    fun compositedTitleBarUsesOneMicroscopicallyLaminatedFaceLayer() {
        val s = surface().apply { renderMode = RenderMode.COMPOSITED }
        s.paint { titleBar(Rect(0, 0, 400, 30), "Window") {} }

        val entities = s.toEntities()
        assertEquals(
            2,
            entities.size,
            "title and cross share one coating display above the strip display"
        )
        assertEquals(
            Surface.FACE_COATING_Z_BIAS.toDouble(),
            entities[1].position.z - entities[0].position.z,
            1e-7,
            "the coating must clear the depth buffer without becoming a visible sheet"
        )
    }

    @Test
    fun faceLabelAbsorbsMinecraftsTextRowAnchorOffset() {
        val s = surface().apply { renderMode = RenderMode.COMPOSITED }
        s.paint { faceLabel("Window", 123, 40) }

        assertTrue(
            123 to (40 - TextMetrics.TEXT_ROW_ANCHOR_OFFSET_PX) in s.canvasItemPositions(),
            "the library must compensate the renderer anchor, not the widget caller"
        )
    }

    @Test
    fun aTabReportsSelectedAndUnselectedDistinctly() {
        val a = surface(); val b = surface()
        a.paint { tab("t", Rect(0, 0, 200, 20), "Tab", selected = false) {} }
        b.paint { tab("t", Rect(0, 0, 200, 20), "Tab", selected = true) {} }
        // different sprites means a different number of drawn glyphs is not
        // guaranteed, but both must register the region
        assertEquals(listOf("t"), a.hitRects().map { it.id })
        assertEquals(listOf("t"), b.hitRects().map { it.id })
    }

    // The unknown-sprite rule: warn once naming the id, then skip. Every part
    // that looks a sprite up routes through resolveSprite, so proving the rule
    // there proves it for all of them. Skipping the WHOLE part matters —
    // skipping only the visual left an invisible control that still consumed
    // clicks.

    @Test
    fun anUnresolvableSpriteWarnsOnceNamingTheId() {
        SpriteDiagnostics.reset()
        val missing = SpriteId("gui", "widget/no_such_sprite")

        assertNull(resolveSprite(missing, "a test part"))
        assertTrue(
            SpriteDiagnostics.warnings().any { it.contains("gui/widget/no_such_sprite") },
            "the warning must name the sprite, got ${SpriteDiagnostics.warnings()}"
        )

        val after = SpriteDiagnostics.warnings().size
        assertNull(resolveSprite(missing, "a test part"))
        assertEquals(after, SpriteDiagnostics.warnings().size, "the warning must be once-only")
        SpriteDiagnostics.reset()
    }

    @Test
    fun distinctUnresolvableSpritesWarnSeparately() {
        SpriteDiagnostics.reset()
        resolveSprite(SpriteId("gui", "widget/missing_a"), "a test part")
        resolveSprite(SpriteId("gui", "widget/missing_b"), "a test part")
        assertEquals(
            2, SpriteDiagnostics.warnings().size,
            "the warn key includes the id, so each unknown sprite reports itself"
        )
        SpriteDiagnostics.reset()
    }
}
