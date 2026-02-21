package io.schemat.displaykit.math

import kotlin.math.sqrt

data class Vec3d(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0
) {
    operator fun plus(other: Vec3d) = Vec3d(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3d) = Vec3d(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Double) = Vec3d(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Double) = Vec3d(x / scalar, y / scalar, z / scalar)

    fun length() = sqrt(x * x + y * y + z * z)
    fun lengthSquared() = x * x + y * y + z * z
    fun normalize(): Vec3d {
        val len = length()
        return if (len == 0.0) this else this / len
    }

    fun dot(other: Vec3d) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3d) = Vec3d(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    fun distance(other: Vec3d) = (this - other).length()
    fun distanceSquared(other: Vec3d) = (this - other).lengthSquared()

    fun toVec3f() = Vec3f(x.toFloat(), y.toFloat(), z.toFloat())

    companion object {
        val ZERO = Vec3d(0.0, 0.0, 0.0)
        val UP = Vec3d(0.0, 1.0, 0.0)
        val DOWN = Vec3d(0.0, -1.0, 0.0)
    }
}
