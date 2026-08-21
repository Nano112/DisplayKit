package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin

/**
 * Placing a [Surface] by its CENTRE instead of its canvas origin.
 *
 * [Surface.position] is canvas pixel (0,0) -- its TOP-LEFT corner, see
 * [SurfacePicking]'s KDoc -- so naively pointing `position` at a target spot
 * (e.g. a point out along a player's look ray) hangs the whole surface
 * down-and-right of that spot instead of centring it there.
 */
object SurfacePlacement {

    /**
     * The [Surface.position] that puts a surface's CENTRE at [center], for a
     * surface with the given [yawDegrees] and world-space [worldWidth] /
     * [worldHeight] (i.e. `widthPx * pixelScale * TextMetrics.PIXEL_SIZE` and
     * likewise for height).
     *
     * Shifts the origin left by half the width and up by half the height.
     * Height is a plain world-Y offset -- [Surface.yawDegrees] only rotates
     * about Y -- but width must be walked back along the surface's own
     * (possibly rotated) local right vector, not world X, or the correction
     * itself ends up pointing the wrong way whenever the surface is turned to
     * face the player.
     *
     * The local right vector is derived from the same forward rotation
     * [Surface.toEntity] applies (`x' = x*cosθ + z*sinθ`, `z' = -x*sinθ +
     * z*cosθ`, per [SurfacePicking.rotateIntoSurfaceLocalSpace]'s KDoc)
     * applied to local +X (canvas rightward): `(cosθ, 0, -sinθ)`.
     */
    fun centeredOrigin(
        center: Vec3d,
        yawDegrees: Float,
        worldWidth: Double,
        worldHeight: Double
    ): Vec3d {
        val theta = Math.toRadians(yawDegrees.toDouble())
        val right = Vec3d(cos(theta), 0.0, -sin(theta))
        val halfWidth = worldWidth / 2.0
        val halfHeight = worldHeight / 2.0
        return Vec3d(
            center.x - right.x * halfWidth,
            center.y + halfHeight,
            center.z - right.z * halfWidth
        )
    }
}
