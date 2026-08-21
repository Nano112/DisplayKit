package io.schemat.displaykit.surface

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the rotation convention `Surface.entityOrigin` hand-rolls against the
 * one JOML actually applies in the transformation matrix.
 *
 * These must agree. `entityOrigin` walks the canvas origin back by hand
 * (trigonometry on yawDegrees) while `toEntity` hands the same yaw to
 * `Matrix4f.rotateY`. If the two disagree in sign, the correction points
 * partly sideways instead of purely along the normal, and the error scales
 * with the offset — which is invisible at yaw 0 (sin == 0) and grows with
 * rotation.
 */
class JomlConventionTest {

    @Test
    fun handRolledRotationMatchesJoml() {
        for (deg in listOf(0.0, 37.0, 90.0, 137.0, 180.0, 271.0)) {
            val th = Math.toRadians(deg)
            val m = Matrix4f().rotateY(th.toFloat())
            val jx = m.transformPosition(Vector3f(1f, 0f, 0f))
            val jz = m.transformPosition(Vector3f(0f, 0f, 1f))
            val c = cos(th)
            val s = sin(th)
            assertTrue(
                Math.abs(jx.x() - c) < 1e-5 && Math.abs(jx.z() - (-s)) < 1e-5,
                "yaw $deg: JOML maps +X to (${jx.x()}, ${jx.z()}), hand-rolled says ($c, ${-s})"
            )
            assertTrue(
                Math.abs(jz.x() - s) < 1e-5 && Math.abs(jz.z() - c) < 1e-5,
                "yaw $deg: JOML maps +Z to (${jz.x()}, ${jz.z()}), hand-rolled says ($s, $c)"
            )
        }
    }
}
