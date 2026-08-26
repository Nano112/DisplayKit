package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PlayerRef

/**
 * A resolved world pose for the centre of an upright [Surface].
 *
 * [Surface.position] is the canvas top-left, which is deliberately not exposed
 * here: anchors describe the point application code actually reasons about and
 * [WorldSurfaceSession] derives the renderer origin from it.
 */
data class SurfacePose(
    val center: Vec3d,
    val yawDegrees: Float
)

/**
 * Supplies the world pose of a surface.
 *
 * Returning null means that the anchor no longer exists and closes the owning
 * [WorldSurfaceSession]. Dynamic anchors are resolved once per host tick;
 * fixed anchors are just a zero-allocation special case of the same contract.
 */
fun interface SurfaceAnchor {
    fun resolve(owner: PlayerRef): SurfacePose?

    companion object {
        /** A fixed centre and renderer yaw. */
        @JvmStatic
        fun fixed(center: Vec3d, yawDegrees: Float): SurfaceAnchor =
            SurfaceAnchor { SurfacePose(center, yawDegrees) }

        /**
         * A fixed centre whose readable side faces a viewer looking along
         * [lookDirection]. This is the same convention as [Surface.yawFacing].
         */
        @JvmStatic
        fun facing(center: Vec3d, lookDirection: Vec3d): SurfaceAnchor =
            fixed(center, Surface.yawFacing(lookDirection))

        /**
         * An upright surface bonded to a vertical world face.
         *
         * [outward] points away from the supporting face. [outset] moves the
         * surface centre along that normal; callers never need to turn it into
         * a top-left origin or a renderer yaw themselves.
         */
        @JvmStatic
        @JvmOverloads
        fun verticalFace(
            center: Vec3d,
            outward: Vec3d,
            outset: Double = 0.0
        ): SurfaceAnchor {
            require(kotlin.math.abs(outward.y) < 1e-9) {
                "An upright surface requires a horizontal face normal (got $outward)."
            }
            val normal = outward.normalize()
            require(normal.lengthSquared() > 0.0) { "A surface face normal cannot be zero." }
            // A player in front of this face looks opposite its outward normal.
            return facing(center + normal * outset, normal * -1.0)
        }

        /** A provider-backed anchor for moving or conditionally present targets. */
        @JvmStatic
        fun dynamic(provider: (PlayerRef) -> SurfacePose?): SurfaceAnchor = SurfaceAnchor(provider)
    }
}
