package io.schemat.displaykit.velocity.packet

import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decomposes an affine matrix into the display entity transform fields:
 * translation, left rotation, per-axis scale, right rotation.
 *
 * The Fabric platform hands this job to Mojang's Transformation, which is not
 * on a proxy's classpath, so this is a clean-room singular value
 * decomposition: A = L * S * R with L and R rotations and S the scale. The
 * common case, a matrix composed from translate, rotate and scale calls, has
 * an identity right rotation and takes the fast path; the full Jacobi
 * eigensolve only runs for genuinely sheared matrices.
 */
object TransformDecomposer {

    data class Decomposed(
        val translation: Vector3f,
        val leftRotation: Quaternionf,
        val scale: Vector3f,
        val rightRotation: Quaternionf,
    )

    fun decompose(matrix: Matrix4f): Decomposed {
        val translation = matrix.getTranslation(Vector3f())
        val linear = Matrix3f(matrix)

        // Fast path: the columns are orthogonal, meaning A = R * diag(s) for
        // some rotation R, so the right rotation is the identity. This is
        // exactly what translate/rotate/scale chains produce.
        val fast = tryOrthogonalColumns(linear)
        if (fast != null) {
            return Decomposed(translation, fast.first, fast.second, Quaternionf())
        }

        return svd(linear, translation)
    }

    private fun tryOrthogonalColumns(a: Matrix3f): Pair<Quaternionf, Vector3f>? {
        val c0 = a.getColumn(0, Vector3f())
        val c1 = a.getColumn(1, Vector3f())
        val c2 = a.getColumn(2, Vector3f())

        val epsilon = 1e-4f * maxOf(c0.lengthSquared(), c1.lengthSquared(), c2.lengthSquared(), 1e-12f)
        if (abs(c0.dot(c1)) > epsilon || abs(c0.dot(c2)) > epsilon || abs(c1.dot(c2)) > epsilon) {
            return null
        }

        var sx = c0.length()
        var sy = c1.length()
        var sz = c2.length()
        if (sx < 1e-12f || sy < 1e-12f || sz < 1e-12f) {
            // A collapsed axis has no recoverable rotation, let the full
            // solver produce something stable instead
            return null
        }

        val rotation = Matrix3f(a)
        rotation.setColumn(0, c0.div(sx))
        rotation.setColumn(1, c1.div(sy))
        rotation.setColumn(2, c2.div(sz))

        // A reflection is not a rotation. Fold the flip into one scale axis so
        // the remaining matrix has determinant one.
        if (rotation.determinant() < 0f) {
            sz = -sz
            rotation.setColumn(2, rotation.getColumn(2, Vector3f()).negate())
        }

        return Pair(rotation.getNormalizedRotation(Quaternionf()), Vector3f(sx, sy, sz))
    }

    /**
     * Full decomposition via the eigensolve of A^T A = R^T * S^2 * R: the
     * eigenvectors give the right rotation, the eigenvalue roots the scale,
     * and the left rotation follows as A * R^T * S^-1.
     */
    private fun svd(a: Matrix3f, translation: Vector3f): Decomposed {
        val ata = Matrix3f(a).transpose().mul(a)

        val (eigenvalues, eigenvectors) = jacobiEigen(ata)

        var sx = sqrt(maxOf(eigenvalues.x, 0f))
        var sy = sqrt(maxOf(eigenvalues.y, 0f))
        var sz = sqrt(maxOf(eigenvalues.z, 0f))

        // Eigenvectors form an orthogonal matrix V with A = L * S * V^T.
        // Ensure V is a rotation, not a reflection.
        if (eigenvectors.determinant() < 0f) {
            eigenvectors.setColumn(2, eigenvectors.getColumn(2, Vector3f()).negate())
        }

        val left = Matrix3f(a).mul(Matrix3f(eigenvectors))
        val leftColumns = arrayOf(
            left.getColumn(0, Vector3f()),
            left.getColumn(1, Vector3f()),
            left.getColumn(2, Vector3f()),
        )
        val scales = floatArrayOf(sx, sy, sz)
        for (i in 0..2) {
            if (scales[i] > 1e-12f) {
                leftColumns[i].div(scales[i])
            } else {
                // Degenerate axis: rebuild it orthogonal to the others
                leftColumns[i].set(leftColumns[(i + 1) % 3]).cross(leftColumns[(i + 2) % 3])
                if (leftColumns[i].lengthSquared() < 1e-12f) leftColumns[i].set(0f, 0f, 1f)
                leftColumns[i].normalize()
            }
        }
        val leftMatrix = Matrix3f()
        leftMatrix.setColumn(0, leftColumns[0])
        leftMatrix.setColumn(1, leftColumns[1])
        leftMatrix.setColumn(2, leftColumns[2])
        if (leftMatrix.determinant() < 0f) {
            sz = -sz
            leftMatrix.setColumn(2, leftMatrix.getColumn(2, Vector3f()).negate())
        }

        // rightRotation is V^T: the client applies left * scale * right
        val rightMatrix = Matrix3f(eigenvectors).transpose()

        return Decomposed(
            translation,
            leftMatrix.getNormalizedRotation(Quaternionf()),
            Vector3f(sx, sy, sz),
            rightMatrix.getNormalizedRotation(Quaternionf()),
        )
    }

    /** Classic 3x3 Jacobi eigensolve for a symmetric matrix. */
    private fun jacobiEigen(symmetric: Matrix3f): Pair<Vector3f, Matrix3f> {
        val m = Array(3) { row -> FloatArray(3) { col -> symmetric.get(col, row) } }
        val v = Array(3) { row -> FloatArray(3) { col -> if (row == col) 1f else 0f } }

        repeat(24) {
            var p = 0
            var q = 1
            var largest = abs(m[0][1])
            if (abs(m[0][2]) > largest) { largest = abs(m[0][2]); p = 0; q = 2 }
            if (abs(m[1][2]) > largest) { largest = abs(m[1][2]); p = 1; q = 2 }
            if (largest < 1e-10f) return@repeat

            val app = m[p][p]
            val aqq = m[q][q]
            val apq = m[p][q]
            val theta = (aqq - app) / (2f * apq)
            val t = (if (theta >= 0f) 1f else -1f) / (abs(theta) + sqrt(theta * theta + 1f))
            val c = 1f / sqrt(t * t + 1f)
            val s = t * c

            for (k in 0..2) {
                val mkp = m[k][p]
                val mkq = m[k][q]
                m[k][p] = c * mkp - s * mkq
                m[k][q] = s * mkp + c * mkq
            }
            for (k in 0..2) {
                val mpk = m[p][k]
                val mqk = m[q][k]
                m[p][k] = c * mpk - s * mqk
                m[q][k] = s * mpk + c * mqk
            }
            for (k in 0..2) {
                val vkp = v[k][p]
                val vkq = v[k][q]
                v[k][p] = c * vkp - s * vkq
                v[k][q] = s * vkp + c * vkq
            }
        }

        val eigenvalues = Vector3f(m[0][0], m[1][1], m[2][2])
        val eigenvectors = Matrix3f(
            v[0][0], v[1][0], v[2][0],
            v[0][1], v[1][1], v[2][1],
            v[0][2], v[1][2], v[2][2],
        )
        return Pair(eigenvalues, eigenvectors)
    }
}
