package io.schemat.displaykit.region

import io.schemat.displaykit.animation.Easing
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3f
import org.joml.Quaternionf

/**
 * A single keyframe in a region animation.
 *
 * @param time Normalized time position (0.0 to 1.0)
 * @param position World position of the region origin at this keyframe
 * @param rotation Rotation of the region at this keyframe
 * @param scale Scale of the region at this keyframe
 * @param easing Easing function to use when interpolating TO this keyframe
 */
data class Keyframe @JvmOverloads constructor(
    val time: Float,
    val position: Vec3d,
    val rotation: Quaternionf = Quaternionf(),
    val scale: Vec3f = Vec3f(1f, 1f, 1f),
    val easing: Easing = Easing.LINEAR,
    val opacity: Float = 1f
) {
    init {
        require(time in 0f..1f) { "Keyframe time must be between 0.0 and 1.0" }
        require(opacity in 0f..1f) { "Keyframe opacity must be between 0.0 and 1.0" }
    }

    companion object {
        /**
         * Create a simple position-only keyframe.
         */
        fun at(time: Float, position: Vec3d, easing: Easing = Easing.LINEAR) =
            Keyframe(time, position, easing = easing)
    }
}
