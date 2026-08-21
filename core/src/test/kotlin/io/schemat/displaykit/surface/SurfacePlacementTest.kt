package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class SurfacePlacementTest {

    private val EPS = 1e-9

    private fun assertVecEquals(expected: Vec3d, actual: Vec3d, msg: String = "") {
        assertEquals(expected.x, actual.x, EPS, "$msg (x)")
        assertEquals(expected.y, actual.y, EPS, "$msg (y)")
        assertEquals(expected.z, actual.z, EPS, "$msg (z)")
    }

    @Test
    fun unrotatedShiftsLeftAlongWorldXAndUpAlongWorldY() {
        val center = Vec3d(10.0, 5.0, 20.0)
        val origin = SurfacePlacement.centeredOrigin(center, 0f, worldWidth = 4.0, worldHeight = 2.0)
        assertVecEquals(Vec3d(8.0, 6.0, 20.0), origin)
    }

    @Test
    fun theOriginPlusHalfWidthAlongRightMinusHalfHeightRecoversTheCenter() {
        // For any yaw, walking from the returned origin back along the local
        // right vector by half the width, then down by half the height, must
        // land exactly back on the requested center. This is the same
        // relationship SurfacePicking relies on to resolve canvas pixels.
        val center = Vec3d(3.0, 1.5, -7.0)
        val width = 6.0
        val height = 3.0
        for (yaw in listOf(0f, 30f, 90f, 137f, -45f, 180f, 359f)) {
            val origin = SurfacePlacement.centeredOrigin(center, yaw, width, height)
            val theta = Math.toRadians(yaw.toDouble())
            val right = Vec3d(cos(theta), 0.0, -sin(theta))
            val recovered = Vec3d(
                origin.x + right.x * (width / 2.0),
                origin.y - height / 2.0,
                origin.z + right.z * (width / 2.0)
            )
            assertVecEquals(center, recovered, "yaw=$yaw")
        }
    }

    @Test
    fun ninetyDegreesShiftsAlongWorldZInsteadOfX() {
        // At yaw=90 the surface's local right vector is world -Z (cos90=0,
        // -sin90=-1), so the horizontal correction must land entirely on z,
        // not x -- this is exactly the bug the surface's own rotation must
        // not be ignored for.
        val center = Vec3d(10.0, 5.0, 20.0)
        val origin = SurfacePlacement.centeredOrigin(center, 90f, worldWidth = 4.0, worldHeight = 2.0)
        assertVecEquals(Vec3d(10.0, 6.0, 22.0), origin)
    }

    @Test
    fun aFullTurnIsANoOpRelativeToUnrotated() {
        val center = Vec3d(1.0, 1.0, 1.0)
        val a = SurfacePlacement.centeredOrigin(center, 0f, 5.0, 3.0)
        val b = SurfacePlacement.centeredOrigin(center, 360f, 5.0, 3.0)
        assertVecEquals(a, b)
    }
}
