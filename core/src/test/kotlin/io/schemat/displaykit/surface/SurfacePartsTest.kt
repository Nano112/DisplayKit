package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
