package io.schemat.displaykit.math

import kotlin.math.sqrt

data class Vec3f(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
) {
    operator fun plus(other: Vec3f) = Vec3f(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3f) = Vec3f(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vec3f(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Float) = Vec3f(x / scalar, y / scalar, z / scalar)

    fun length() = sqrt(x * x + y * y + z * z)
    fun normalize(): Vec3f {
        val len = length()
        return if (len == 0f) this else this / len
    }

    fun toVec3d() = Vec3d(x.toDouble(), y.toDouble(), z.toDouble())

    companion object {
        val ZERO = Vec3f(0f, 0f, 0f)
    }
}
