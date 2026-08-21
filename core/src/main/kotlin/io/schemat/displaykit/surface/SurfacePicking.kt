package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.TextMetrics
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Turns a look ray into a pixel on a surface.
 *
 * A FIXED surface lies in the world XY plane facing -Z, matching an unrotated
 * text display. Canvas pixel (0,0) is its TOP-LEFT, so canvas y runs opposite
 * to world y.
 *
 * Only FIXED surfaces can be picked: a billboarded plane rotates per viewer, so
 * the server cannot know its orientation.
 */
object SurfacePicking {

    /**
     * Guards the pixel-index floor against the rounding noise that
     * [Surface.pixelScale] (a Float) introduces once widened to Double. A hit
     * that lands exactly on a pixel boundary in exact arithmetic can come out
     * a few ulps low (e.g. 24.999998 instead of 25.0); nudging the value up by
     * a fraction of a pixel before flooring corrects that without affecting
     * any genuinely fractional position, which sits nowhere this close to an
     * integer boundary in the callers this is built for.
     */
    private const val PIXEL_EPSILON = 1e-4

    fun localPixel(
        surface: Surface,
        eye: Vec3d,
        look: Vec3d,
        maxDistance: Double = 12.0
    ): Pair<Int, Int>? {
        // world units per canvas pixel
        val unit = (surface.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        if (unit <= 0.0) return null

        // Surface.toEntity() rotates the whole surface by yawDegrees about
        // its own position. Counter-rotating the incoming ray by the same
        // angle, about the same point, maps it into the surface's local
        // (unrotated, -Z-facing) space, where the rest of this method's
        // planar maths — unchanged since before yaw existed — applies as-is.
        val (localEye, localLook) = rotateIntoSurfaceLocalSpace(surface, eye, look)

        val planeZ = surface.position.z
        if (abs(localLook.z) < 1e-6) return null

        val t = (planeZ - localEye.z) / localLook.z
        if (t < 0.0 || t > maxDistance) return null

        val hx = localEye.x + localLook.x * t
        val hy = localEye.y + localLook.y * t

        val px = floor((hx - surface.position.x) / unit + PIXEL_EPSILON).toInt()
        val py = floor((surface.position.y - hy) / unit + PIXEL_EPSILON).toInt()   // canvas y grows downward

        if (px < 0 || py < 0 || px >= surface.widthPx || py >= surface.heightPx) return null
        return px to py
    }

    /**
     * Rotates [eye] and [look] by `-surface.yawDegrees` about [Surface.position]
     * (a pure direction, so only its rotation matters for [look]), so the
     * result can be handled as though the surface had never rotated.
     *
     * Uses the same right-handed Y-axis rotation JOML's `Matrix4f.rotateY`
     * applies in [Surface.toEntity] — `x' = x*cosθ + z*sinθ`, `z' = -x*sinθ +
     * z*cosθ` — negated here to invert it. A no-op (returns the inputs
     * unchanged) when `yawDegrees == 0f`, which is also what the trig would
     * produce, so callers never need to special-case the default.
     */
    private fun rotateIntoSurfaceLocalSpace(surface: Surface, eye: Vec3d, look: Vec3d): Pair<Vec3d, Vec3d> {
        if (surface.yawDegrees == 0f) return eye to look

        val theta = Math.toRadians(-surface.yawDegrees.toDouble())
        val cos = cos(theta)
        val sin = sin(theta)
        fun rotate(v: Vec3d) = Vec3d(v.x * cos + v.z * sin, v.y, -v.x * sin + v.z * cos)

        val p = surface.position
        val relativeEye = Vec3d(eye.x - p.x, eye.y - p.y, eye.z - p.z)
        val rotatedRelativeEye = rotate(relativeEye)
        val rotatedEye = Vec3d(p.x + rotatedRelativeEye.x, p.y + rotatedRelativeEye.y, p.z + rotatedRelativeEye.z)

        return rotatedEye to rotate(look)
    }

    /** The topmost region under the ray — later-drawn regions win. */
    fun hit(
        surface: Surface,
        eye: Vec3d,
        look: Vec3d,
        maxDistance: Double = 12.0
    ): HitRect? {
        val (px, py) = localPixel(surface, eye, look, maxDistance) ?: return null
        return surface.hitRects().lastOrNull { it.rect.contains(px, py) }
    }
}
