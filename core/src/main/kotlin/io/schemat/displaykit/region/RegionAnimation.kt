package io.schemat.displaykit.region

import io.schemat.displaykit.animation.Easing
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3f
import org.joml.Quaternionf

/**
 * A complete animation definition for a captured region.
 *
 * @param capture The captured blocks to animate
 * @param keyframes List of keyframes defining the animation path (sorted by time)
 * @param durationTicks Total duration of the animation in game ticks
 */
class RegionAnimation(
    val capture: RegionCapture,
    keyframes: List<Keyframe>,
    val durationTicks: Int
) {
    /**
     * Keyframes sorted by time.
     */
    val keyframes: List<Keyframe> = keyframes.sortedBy { it.time }

    init {
        require(keyframes.isNotEmpty()) { "Animation must have at least one keyframe" }
        require(durationTicks > 0) { "Duration must be positive" }
    }

    /**
     * Get the interpolated transform at a given tick.
     *
     * @param tick Current tick (0 to durationTicks)
     * @return Interpolated position, rotation, and scale
     */
    fun interpolate(tick: Int): AnimationFrame {
        val t = (tick.toFloat() / durationTicks).coerceIn(0f, 1f)
        return interpolateNormalized(t)
    }

    /**
     * Get the interpolated transform at a normalized time.
     *
     * @param t Normalized time (0.0 to 1.0)
     * @return Interpolated position, rotation, and scale
     */
    fun interpolateNormalized(t: Float): AnimationFrame {
        // Find surrounding keyframes
        val (prevKf, nextKf) = findKeyframePair(t)

        // If we're at or past the end, return the last keyframe
        if (prevKf === nextKf) {
            return AnimationFrame(prevKf.position, prevKf.rotation, prevKf.scale, prevKf.opacity)
        }

        // Calculate local progress between these two keyframes
        val localT = if (nextKf.time == prevKf.time) {
            1f
        } else {
            (t - prevKf.time) / (nextKf.time - prevKf.time)
        }

        // Apply easing (using the target keyframe's easing)
        val easedT = nextKf.easing.apply(localT)

        // Interpolate each component
        return AnimationFrame(
            position = lerp(prevKf.position, nextKf.position, easedT),
            rotation = slerp(prevKf.rotation, nextKf.rotation, easedT),
            scale = lerp(prevKf.scale, nextKf.scale, easedT),
            opacity = prevKf.opacity + (nextKf.opacity - prevKf.opacity) * easedT
        )
    }

    private fun findKeyframePair(t: Float): Pair<Keyframe, Keyframe> {
        // Edge cases
        if (keyframes.size == 1) return keyframes[0] to keyframes[0]
        if (t <= keyframes.first().time) return keyframes[0] to keyframes[0]
        if (t >= keyframes.last().time) return keyframes.last() to keyframes.last()

        // Find the pair
        for (i in 0 until keyframes.size - 1) {
            if (keyframes[i + 1].time >= t) {
                return keyframes[i] to keyframes[i + 1]
            }
        }

        return keyframes.last() to keyframes.last()
    }

    private fun lerp(a: Vec3d, b: Vec3d, t: Float): Vec3d {
        val td = t.toDouble()
        return Vec3d(
            a.x + (b.x - a.x) * td,
            a.y + (b.y - a.y) * td,
            a.z + (b.z - a.z) * td
        )
    }

    private fun lerp(a: Vec3f, b: Vec3f, t: Float): Vec3f {
        return Vec3f(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        )
    }

    private fun slerp(a: Quaternionf, b: Quaternionf, t: Float): Quaternionf {
        val result = Quaternionf()
        a.slerp(b, t, result)
        return result
    }

    companion object {
        /**
         * Create a simple A-to-B animation.
         */
        fun linear(
            capture: RegionCapture,
            from: Vec3d,
            to: Vec3d,
            durationTicks: Int,
            easing: Easing = Easing.EASE_IN_OUT_CUBIC
        ): RegionAnimation {
            return RegionAnimation(
                capture = capture,
                keyframes = listOf(
                    Keyframe(0f, from),
                    Keyframe(1f, to, easing = easing)
                ),
                durationTicks = durationTicks
            )
        }
    }
}

/**
 * A single frame of animation output.
 */
data class AnimationFrame(
    val position: Vec3d,
    val rotation: Quaternionf,
    val scale: Vec3f,
    val opacity: Float = 1f
)
