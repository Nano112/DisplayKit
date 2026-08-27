package io.schemat.displaykit.velocity.packet

import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The decomposition is correct when recomposing translation, left rotation,
 * scale and right rotation reproduces the original matrix, which is also
 * exactly how the client consumes the four fields.
 */
class TransformDecomposerTest {

    private fun recompose(d: TransformDecomposer.Decomposed): Matrix4f =
        Matrix4f()
            .translate(d.translation)
            .rotate(d.leftRotation)
            .scale(d.scale)
            .rotate(d.rightRotation)

    private fun assertMatricesClose(expected: Matrix4f, actual: Matrix4f, tolerance: Float = 1e-4f) {
        val a = FloatArray(16).also { expected.get(it) }
        val b = FloatArray(16).also { actual.get(it) }
        for (i in 0..15) {
            assertTrue(
                abs(a[i] - b[i]) < tolerance,
                "matrix element $i differs: ${a[i]} vs ${b[i]}\nexpected $expected\nactual $actual"
            )
        }
    }

    @Test
    fun `identity decomposes to identity`() {
        val d = TransformDecomposer.decompose(Matrix4f())
        assertMatricesClose(Matrix4f(), recompose(d))
        assertTrue(abs(d.scale.x - 1f) < 1e-5f)
        assertTrue(abs(d.rightRotation.w - 1f) < 1e-5f, "identity right rotation on the fast path")
    }

    @Test
    fun `translate rotate scale takes the fast path exactly`() {
        val matrix = Matrix4f()
            .translate(1.5f, -2f, 0.25f)
            .rotate(Quaternionf().rotationY(0.7f))
            .scale(2f, 0.5f, 3f)

        val d = TransformDecomposer.decompose(matrix)
        assertMatricesClose(matrix, recompose(d))
        assertTrue(abs(d.rightRotation.w - 1f) < 1e-4f, "TRS chains need no right rotation")
    }

    @Test
    fun `uniform scale with arbitrary axis rotation recomposes`() {
        val matrix = Matrix4f()
            .rotate(Quaternionf().rotationAxis(1.1f, Vector3f(1f, 1f, 0f).normalize()))
            .scale(0.85f)

        assertMatricesClose(matrix, recompose(TransformDecomposer.decompose(matrix)))
    }

    @Test
    fun `sheared matrix recomposes through the full decomposition`() {
        // scale-then-rotate-then-scale produces genuine shear in TRS terms
        val matrix = Matrix4f()
            .scale(2f, 1f, 1f)
            .rotate(Quaternionf().rotationZ(0.6f))
            .scale(1f, 3f, 1f)

        assertMatricesClose(matrix, recompose(TransformDecomposer.decompose(matrix)), 1e-3f)
    }

    @Test
    fun `reflection folds into a negative scale not a broken rotation`() {
        val matrix = Matrix4f().scale(1f, 1f, -1f)

        val d = TransformDecomposer.decompose(matrix)
        assertMatricesClose(matrix, recompose(d))

        // Quaternions must stay unit-length rotations
        val leftLength = with(d.leftRotation) { x * x + y * y + z * z + w * w }
        assertTrue(abs(leftLength - 1f) < 1e-4f)
    }

    @Test
    fun `what the surface painter emits round trips`() {
        // The shape Surface.toEntities actually produces: pixel scale plus
        // a facing rotation plus a centring translation
        val matrix = Matrix4f()
            .translate(-1.2f, 0.9f, 0.01f)
            .rotate(Quaternionf().rotationY(2.35f))
            .scale(0.0125f, 0.0125f, 0.0125f)

        assertMatricesClose(matrix, recompose(TransformDecomposer.decompose(matrix)))
    }
}
