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

    /**
     * The [Surface.position] that centres a surface [distance] blocks along a
     * viewer's look ray -- i.e. squarely under their crosshair.
     *
     * Walks the FULL look vector, [Vec3d.y] included. Both windows previously
     * built this inline as
     * `Vec3d(eye.x + look.x * d, eye.y, eye.z + look.z * d)`, dropping the
     * vertical component, which pinned every panel to eye height however far
     * up or down the viewer was looking. Open a window while looking even
     * slightly downward and it appeared above the crosshair, so you had to
     * look up to hover it -- which is most of why the on-surface cursor felt
     * broken before it actually was.
     *
     * Dropping `look.y` also shortened the panel's apparent distance as the
     * pitch steepened, because `look.x`/`look.z` shrink toward zero: at a
     * steep angle the window flew into the viewer's face. Using the whole
     * vector keeps [distance] the true distance at every pitch.
     *
     * The surface itself stays vertical -- [Surface.yawDegrees] rotates about
     * Y only -- so a panel placed above or below eye level does not tilt. That
     * is deliberate: a UI that pitches with the viewer is far harder to read
     * than one that stays upright.
     */
    fun inFrontOf(
        eye: Vec3d,
        look: Vec3d,
        distance: Double,
        yawDegrees: Float,
        worldWidth: Double,
        worldHeight: Double
    ): Vec3d = centeredOrigin(
        center = Vec3d(
            eye.x + look.x * distance,
            eye.y + look.y * distance,
            eye.z + look.z * distance
        ),
        yawDegrees = yawDegrees,
        worldWidth = worldWidth,
        worldHeight = worldHeight
    )
}
