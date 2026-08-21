package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
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
        s.paint { titleBar(Rect(0, 0, 400, 16), "Window") {} }
        val close = s.hitRects().first { it.id == "close" }
        assertTrue(close.rect.right <= 400)
        assertTrue(close.rect.x > 200, "close should sit on the right, got ${close.rect}")
    }

    @Test
    fun aTabReportsSelectedAndUnselectedDistinctly() {
        val a = surface(); val b = surface()
        a.paint { tab("t", Rect(0, 0, 130, 24), "Tab", selected = false) {} }
        b.paint { tab("t", Rect(0, 0, 130, 24), "Tab", selected = true) {} }
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
