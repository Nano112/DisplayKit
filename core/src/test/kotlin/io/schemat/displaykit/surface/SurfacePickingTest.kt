package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SurfacePickingTest {

    /** 100x100 px at 1 block wide, so 1 px = 0.01 blocks. Origin at world (0,70,0). */
    private fun surface() = Surface(100, 100, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 1f)

    private val straightAhead = Vec3d(0.0, 0.0, 1.0)

    @Test
    fun aRayStraightAtTheOriginHitsTheTopLeftPixel() {
        val s = surface()
        val p = SurfacePicking.localPixel(s, Vec3d(0.0, 70.0, -2.0), straightAhead)
        assertEquals(0 to 0, p)
    }

    @Test
    fun movingRightAlongXAdvancesTheCanvasXPixel() {
        val s = surface()
        // +0.25 blocks at 0.01 blocks/px is 25 px
        val p = SurfacePicking.localPixel(s, Vec3d(0.25, 70.0, -2.0), straightAhead)
        assertEquals(25, p!!.first)
    }

    @Test
    fun movingDownInWorldAdvancesTheCanvasYPixel() {
        val s = surface()
        // canvas y grows downward, so a lower world y is a larger canvas y
        val p = SurfacePicking.localPixel(s, Vec3d(0.0, 69.7, -2.0), straightAhead)
        assertEquals(30, p!!.second)
    }

    @Test
    fun aRayMissingTheRectangleReturnsNull() {
        val s = surface()
        assertNull(SurfacePicking.localPixel(s, Vec3d(5.0, 70.0, -2.0), straightAhead))
    }

    @Test
    fun aRayPointingAwayFromThePlaneReturnsNull() {
        val s = surface()
        assertNull(SurfacePicking.localPixel(s, Vec3d(0.0, 70.0, -2.0), Vec3d(0.0, 0.0, -1.0)))
    }

    @Test
    fun aPlaneBeyondMaxDistanceIsNotHit() {
        val s = surface()
        assertNull(SurfacePicking.localPixel(s, Vec3d(0.0, 70.0, -50.0), straightAhead, maxDistance = 12.0))
    }

    @Test
    fun hitReturnsTheTopmostOverlappingRegion() {
        val s = surface()
        s.paint {
            region("under", Rect(0, 0, 60, 60)) {}
            region("over", Rect(0, 0, 30, 30)) {}
        }
        val h = SurfacePicking.hit(s, Vec3d(0.05, 69.95, -2.0), straightAhead)
        assertNotNull(h)
        assertEquals("over", h.id, "later-drawn regions sit on top")
    }

    @Test
    fun hitReturnsNullWhereNoRegionCovers() {
        val s = surface()
        s.paint { region("only", Rect(0, 0, 10, 10)) {} }
        assertNull(SurfacePicking.hit(s, Vec3d(0.5, 69.5, -2.0), straightAhead))
    }

    // --- Feature: yawDegrees ---

    @Test
    fun defaultYawLeavesPickingUnchanged() {
        // yawDegrees defaults to 0f, and the counter-rotation is a no-op at
        // that value -- this is really the same assertion as
        // aRayStraightAtTheOriginHitsTheTopLeftPixel, spelled out to make the
        // "yaw=0 is unchanged" guarantee explicit.
        val s = surface()
        assertEquals(0f, s.yawDegrees)
        assertEquals(0 to 0, SurfacePicking.localPixel(s, Vec3d(0.0, 70.0, -2.0), straightAhead))
    }

    @Test
    fun rotatingTheSurfaceAndMovingTheViewerCorrespondinglyHitsTheSamePixel() {
        // Base case: viewer at z=-2 looking straight ahead (+Z) hits the
        // top-left pixel of an unrotated surface (see
        // aRayStraightAtTheOriginHitsTheTopLeftPixel above).
        val base = surface()
        val basePixel = SurfacePicking.localPixel(base, Vec3d(0.0, 70.0, -2.0), straightAhead)
        assertEquals(0 to 0, basePixel)

        // Rotate the surface 90 degrees about its own position, then move the
        // viewer to the position/look that is the SAME rigid rotation of the
        // base eye/look about that position -- eye (0,70,-2) and look
        // (0,0,1) both rotate to eye (-2,70,0) and look (1,0,0) under a +90
        // degree turn. A viewer square to the rotated surface must still hit
        // the same canvas pixel.
        val rotated = surface().apply { yawDegrees = 90f }
        val rotatedPixel = SurfacePicking.localPixel(rotated, Vec3d(-2.0, 70.0, 0.0), Vec3d(1.0, 0.0, 0.0))

        assertEquals(basePixel, rotatedPixel)
    }
}
