package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A window must open under the crosshair, at any pitch.
 *
 * Both windows previously built their centre inline as
 * `Vec3d(eye.x + look.x * d, eye.y, eye.z + look.z * d)`, dropping the
 * vertical component. Two consequences, both reported in-world: a window
 * opened while looking even slightly downward appeared ABOVE the crosshair,
 * so it had to be hunted for before it could be hovered; and because
 * `look.x`/`look.z` shrink as pitch steepens, the window also flew closer
 * the more steeply you looked.
 */
class SurfacePlacementInFrontOfTest {

    private val eye = Vec3d(10.0, 70.0, -4.0)
    private val w = 3.0
    private val h = 2.0

    /** Unit look vector for a yaw/pitch, matching Minecraft's convention. */
    private fun look(yawDeg: Double, pitchDeg: Double): Vec3d {
        val y = Math.toRadians(yawDeg)
        val p = Math.toRadians(pitchDeg)
        return Vec3d(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    /** Undo centeredOrigin to recover the centre a position was built from. */
    private fun centreOf(pos: Vec3d, yawDeg: Float): Vec3d {
        val t = Math.toRadians(yawDeg.toDouble())
        val right = Vec3d(cos(t), 0.0, -sin(t))
        return Vec3d(pos.x + right.x * (w / 2), pos.y - h / 2, pos.z + right.z * (w / 2))
    }

    @Test
    fun theCentreLandsExactlyOnTheLookRay() {
        for (pitch in listOf(-60.0, -30.0, -5.0, 0.0, 5.0, 30.0, 60.0)) {
            for (yaw in listOf(0.0, 90.0, 213.0)) {
                val l = look(yaw, pitch)
                val d = 4.0
                val pos = SurfacePlacement.inFrontOf(eye, l, d, yaw.toFloat(), w, h)
                val centre = centreOf(pos, yaw.toFloat())
                val expected = Vec3d(eye.x + l.x * d, eye.y + l.y * d, eye.z + l.z * d)

                assertTrue(
                    centre.distance(expected) < 1e-9,
                    "yaw $yaw pitch $pitch: centre $centre must sit on the look ray at $expected"
                )
            }
        }
    }

    @Test
    fun lookingDownPutsTheWindowBelowEyeLevelNotAtIt() {
        // The reported symptom, stated directly.
        val l = look(0.0, 30.0) // 30 degrees downward
        val pos = SurfacePlacement.inFrontOf(eye, l, 4.0, 0f, w, h)
        val centre = centreOf(pos, 0f)

        assertTrue(
            centre.y < eye.y - 1.0,
            "looking 30 degrees down must place the window below eye level, got y=${centre.y} vs eye ${eye.y}"
        )
    }

    @Test
    fun theDistanceIsConstantWhateverThePitch() {
        // Dropping look.y also shortened the apparent distance as pitch
        // steepened, because look.x/look.z shrink toward zero -- at a steep
        // angle the panel arrived in the viewer's face.
        val d = 4.0
        val distances = listOf(0.0, 20.0, 45.0, 70.0).map { pitch ->
            val l = look(0.0, pitch)
            centreOf(SurfacePlacement.inFrontOf(eye, l, d, 0f, w, h), 0f).distance(eye)
        }
        for ((i, got) in distances.withIndex()) {
            assertEquals(d, got, 1e-9, "pitch index $i: distance must stay $d, got $got")
        }
    }

    @Test
    fun atZeroPitchItMatchesTheOldEyeLevelBehaviour() {
        // The one case the previous code got right, kept as a regression
        // anchor so the fix is provably a strict extension of it.
        val l = look(37.0, 0.0)
        val d = 5.0
        val fresh = SurfacePlacement.inFrontOf(eye, l, d, 37f, w, h)
        val legacy = SurfacePlacement.centeredOrigin(
            Vec3d(eye.x + l.x * d, eye.y, eye.z + l.z * d), 37f, w, h
        )
        assertTrue(fresh.distance(legacy) < 1e-9, "level look must be unchanged: $fresh vs $legacy")
    }

    @Test
    fun aStraightDownLookStillPlacesAnUprightWindow() {
        // Surface.yawDegrees rotates about Y only, so the panel cannot tilt.
        // Worth pinning: the placement must not silently degenerate when the
        // look ray is nearly parallel to the world up axis.
        val l = look(0.0, 89.0)
        val pos = SurfacePlacement.inFrontOf(eye, l, 4.0, 0f, w, h)
        val centre = centreOf(pos, 0f)

        assertTrue(centre.y < eye.y - 3.0, "should be almost directly below the viewer")
        assertTrue(abs(centre.x - eye.x) < 0.2, "and almost directly beneath, not off to one side")
    }
}
