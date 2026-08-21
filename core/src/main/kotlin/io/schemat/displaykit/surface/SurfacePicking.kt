package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.TextMetrics
import kotlin.math.abs
import kotlin.math.floor

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

        val planeZ = surface.position.z
        if (abs(look.z) < 1e-6) return null

        val t = (planeZ - eye.z) / look.z
        if (t < 0.0 || t > maxDistance) return null

        val hx = eye.x + look.x * t
        val hy = eye.y + look.y * t

        val px = floor((hx - surface.position.x) / unit + PIXEL_EPSILON).toInt()
        val py = floor((surface.position.y - hy) / unit + PIXEL_EPSILON).toInt()   // canvas y grows downward

        if (px < 0 || py < 0 || px >= surface.widthPx || py >= surface.heightPx) return null
        return px to py
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
