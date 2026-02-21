package io.schemat.displaykit.math

import org.joml.Matrix4f
import org.joml.Quaternionf

data class Mat4f(val joml: Matrix4f = Matrix4f()) {

    fun translate(x: Float, y: Float, z: Float): Mat4f =
        Mat4f(Matrix4f(joml).translate(x, y, z))

    fun scale(x: Float, y: Float, z: Float): Mat4f =
        Mat4f(Matrix4f(joml).scale(x, y, z))

    fun scale(s: Float): Mat4f = scale(s, s, s)

    fun rotate(quaternion: Quaternionf): Mat4f =
        Mat4f(Matrix4f(joml).rotate(quaternion))

    fun multiply(other: Mat4f): Mat4f =
        Mat4f(Matrix4f(joml).mul(other.joml))

    fun toFloatArray(): FloatArray {
        val arr = FloatArray(16)
        joml.get(arr)
        return arr
    }

    companion object {
        fun identity() = Mat4f(Matrix4f())

        fun translation(x: Float, y: Float, z: Float) =
            Mat4f(Matrix4f().translation(x, y, z))

        fun scaling(x: Float, y: Float, z: Float) =
            Mat4f(Matrix4f().scaling(x, y, z))
    }
}
