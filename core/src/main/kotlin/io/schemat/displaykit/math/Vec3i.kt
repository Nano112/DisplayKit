package io.schemat.displaykit.math

data class Vec3i(
    val x: Int = 0,
    val y: Int = 0,
    val z: Int = 0
) {
    operator fun plus(other: Vec3i) = Vec3i(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3i) = Vec3i(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Int) = Vec3i(x * scalar, y * scalar, z * scalar)

    fun toVec3d() = Vec3d(x.toDouble(), y.toDouble(), z.toDouble())
    fun toVec3f() = Vec3f(x.toFloat(), y.toFloat(), z.toFloat())

    companion object {
        val ZERO = Vec3i(0, 0, 0)
    }
}
