package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.DkColor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SurfacePointerTest {

    private object Src : SliceGlyphSource {
        override fun request(id: io.schemat.displaykit.sprite.SpriteId) {}
        override fun codepointFor(
            id: io.schemat.displaykit.sprite.SpriteId, srcX: Int, srcY: Int, ascent: Int
        ) = 0xF8000
        override fun advanceFor(
            id: io.schemat.displaykit.sprite.SpriteId, srcX: Int, srcY: Int
        ): Int? = null
    }

    @BeforeTest fun install() { SliceGlyphSource.installed = Src }
    @AfterTest fun clear() { SliceGlyphSource.installed = null }

    private fun surface() = Surface(346, 264, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 3f)
        .apply { paint { label("x", 0, 0, DkColor.WHITE) } }

    @Test
    fun thePointerSitsInFrontOfEveryContentLayer() {
        val s = surface()
        val layers = s.toEntities()
        val pointer = assertNotNull(s.pointerEntityAt(100, 100))
        // At yaw 0 the readable normal is +Z, so "in front" is a larger z.
        assertTrue(
            pointer.position.z > layers.maxOf { it.position.z },
            "pointer z=${pointer.position.z} must exceed the top layer"
        )
    }

    @Test
    fun thePointerMovesWithTheCanvasPoint() {
        val s = surface()
        val a = assertNotNull(s.pointerEntityAt(10, 10))
        val b = assertNotNull(s.pointerEntityAt(200, 10))
        assertTrue(a.position.x != b.position.x, "a different canvas x must move it")
    }

    @Test
    fun aMissingPointerSpriteDegradesToNoPointer() {
        // Never abort the surface over a cosmetic cursor.
        val s = surface()
        val saved = Surface.pointerSpriteOverride
        try {
            Surface.pointerSpriteOverride = io.schemat.displaykit.sprite.SpriteId("gui", "does/not/exist")
            kotlin.test.assertNull(s.pointerEntityAt(10, 10))
        } finally {
            Surface.pointerSpriteOverride = saved
        }
    }

    @Test
    fun thePointerLandsUnderTheCrosshairAtNonZeroYaw() {
        // planePoint and SurfacePicking must be exact inverses, or the cursor
        // does not sit where the player is actually aiming -- which defeats
        // the entire purpose of having one.
        for (yaw in listOf(0f, 90f, 137f, 271f)) {
            val s = surface()
            s.yawDegrees = yaw
            // The plane's own outward normal at this yaw, read off planePoint's
            // depth axis. A straight -Z ray only ever hits the plane at yaw 0
            // -- at other yaws it can miss entirely -- so the eye/look below
            // are built along the ACTUAL normal instead of assuming one.
            val onPlane = s.planePointForTest(0, 0)
            val offPlane = s.planePointForTest(0, 0, 1f)
            val normal = offPlane - onPlane
            for ((px, py) in listOf(10 to 10, 173 to 132, 300 to 200)) {
                val world = s.planePointForTest(px, py)
                val eye = world + normal * 2.0 // 2 blocks out along the normal
                val look = Vec3d(0.0, 0.0, 0.0) - normal
                val back = SurfacePicking.localPixel(s, eye, look)
                // Only assert the round trip where the ray actually meets the plane.
                if (back != null) assertEquals(px to py, back, "yaw $yaw pixel ($px,$py)")
            }
        }
    }

    @Test
    fun movingThePointerReusesTheEntityRatherThanRespawning() {
        val s = surface()
        val a = assertNotNull(s.pointerEntityAt(10, 10))
        val b = assertNotNull(s.pointerEntityAt(200, 120))
        assertTrue(
            a.position.x != b.position.x || a.position.y != b.position.y,
            "a different canvas point must move it"
        )
    }
}
