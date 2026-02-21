package io.schemat.displaykit.animation

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.DkColor

/**
 * A value that smoothly interpolates from its current state to a target state.
 *
 * Usage:
 * ```kotlin
 * val alpha = AnimatedValue.ofFloat(1f)
 * alpha.target = 0f  // Will animate from 1 to 0
 * alpha.duration = 200L
 * alpha.easing = Easing.EASE_OUT_CUBIC
 *
 * // Each tick:
 * display.alpha = alpha.current
 * ```
 *
 * @param T The type of value being animated
 * @param initial The starting value
 * @param lerp Function to interpolate between two values
 */
class AnimatedValue<T>(
    initial: T,
    private val lerp: (from: T, to: T, progress: Float) -> T
) {
    private var startValue: T = initial
    private var startTime: Long = 0L
    private var _target: T = initial

    /** The target value to animate towards */
    var target: T
        get() = _target
        set(value) {
            if (_target != value) {
                startValue = current
                startTime = System.currentTimeMillis()
                _target = value
            }
        }

    /** Animation duration in milliseconds */
    var duration: Long = 200L

    /** Easing function for the animation */
    var easing: Easing = Easing.DEFAULT

    /** The current interpolated value */
    val current: T
        get() {
            if (startTime == 0L) return _target

            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= duration) return _target

            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            val easedProgress = easing.apply(progress)
            return lerp(startValue, _target, easedProgress)
        }

    /** Whether the animation is currently in progress */
    val isAnimating: Boolean
        get() = startTime > 0L && System.currentTimeMillis() - startTime < duration

    /** Whether the animation has completed */
    val isComplete: Boolean
        get() = !isAnimating

    /**
     * Immediately set the value without animation.
     */
    fun set(value: T) {
        startValue = value
        _target = value
        startTime = 0L
    }

    /**
     * Animate to a target with custom duration and easing.
     */
    fun animateTo(
        value: T,
        duration: Long = this.duration,
        easing: Easing = this.easing
    ) {
        this.duration = duration
        this.easing = easing
        this.target = value
    }

    /**
     * Reset the animation, snapping to the target value.
     */
    fun snap() {
        startValue = _target
        startTime = 0L
    }

    companion object {
        /**
         * Create an AnimatedValue for Float.
         */
        fun ofFloat(initial: Float = 0f): AnimatedValue<Float> {
            return AnimatedValue(initial) { from, to, progress ->
                from + (to - from) * progress
            }
        }

        /**
         * Create an AnimatedValue for Double.
         */
        fun ofDouble(initial: Double = 0.0): AnimatedValue<Double> {
            return AnimatedValue(initial) { from, to, progress ->
                from + (to - from) * progress
            }
        }

        /**
         * Create an AnimatedValue for Int.
         */
        fun ofInt(initial: Int = 0): AnimatedValue<Int> {
            return AnimatedValue(initial) { from, to, progress ->
                (from + (to - from) * progress).toInt()
            }
        }

        /**
         * Create an AnimatedValue for Vec3d (position/translation).
         */
        fun ofVec3d(initial: Vec3d = Vec3d.ZERO): AnimatedValue<Vec3d> {
            return AnimatedValue(initial) { from, to, progress ->
                Vec3d(
                    from.x + (to.x - from.x) * progress,
                    from.y + (to.y - from.y) * progress,
                    from.z + (to.z - from.z) * progress
                )
            }
        }

        /**
         * Create an AnimatedValue for DkColor with RGBA interpolation.
         */
        fun ofColor(initial: DkColor = DkColor.WHITE): AnimatedValue<DkColor> {
            return AnimatedValue(initial) { from, to, progress ->
                DkColor(
                    alpha = (from.alpha + (to.alpha - from.alpha) * progress).toInt().coerceIn(0, 255),
                    red = (from.red + (to.red - from.red) * progress).toInt().coerceIn(0, 255),
                    green = (from.green + (to.green - from.green) * progress).toInt().coerceIn(0, 255),
                    blue = (from.blue + (to.blue - from.blue) * progress).toInt().coerceIn(0, 255)
                )
            }
        }

        /**
         * Create a spring-like animation for Float values.
         * Uses a critically damped spring for smooth, natural motion.
         */
        fun springFloat(
            initial: Float = 0f,
            stiffness: Float = 100f,
            damping: Float = 10f
        ): SpringAnimatedValue {
            return SpringAnimatedValue(initial, stiffness, damping)
        }
    }
}

/**
 * A spring-based animated value that provides more natural motion.
 *
 * Uses a simplified spring physics model that updates each tick.
 */
class SpringAnimatedValue(
    initial: Float,
    private val stiffness: Float = 100f,
    private val damping: Float = 10f
) {
    private var position: Float = initial
    private var velocity: Float = 0f
    private var _target: Float = initial

    var target: Float
        get() = _target
        set(value) {
            _target = value
        }

    val current: Float
        get() = position

    val isAnimating: Boolean
        get() {
            val positionDiff = kotlin.math.abs(position - _target)
            val velocityMag = kotlin.math.abs(velocity)
            return positionDiff > 0.001f || velocityMag > 0.001f
        }

    /**
     * Update the spring simulation. Call once per tick.
     * @param deltaTime Time since last update in seconds (1/20 for Minecraft tick)
     */
    fun update(deltaTime: Float = 0.05f) {
        val displacement = position - _target
        val springForce = -stiffness * displacement
        val dampingForce = -damping * velocity
        val acceleration = springForce + dampingForce

        velocity += acceleration * deltaTime
        position += velocity * deltaTime
    }

    /**
     * Immediately set the position without animation.
     */
    fun set(value: Float) {
        position = value
        _target = value
        velocity = 0f
    }

    /**
     * Add an impulse to the spring (useful for "bounce" effects).
     */
    fun impulse(force: Float) {
        velocity += force
    }
}
