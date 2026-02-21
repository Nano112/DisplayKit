package io.schemat.displaykit.animation

import java.util.concurrent.ConcurrentHashMap

/**
 * Manages animation updates for UI elements.
 *
 * Elements register themselves when they start animating, and the ticker
 * processes all active animations each tick. Elements are automatically
 * unregistered when their animations complete.
 *
 * Usage:
 * 1. Register animatable elements: `AnimationTicker.register(element)`
 * 2. Call `AnimationTicker.tick()` each server tick
 * 3. Elements are auto-unregistered when animations complete
 */
object AnimationTicker {
    private val animating = ConcurrentHashMap.newKeySet<Animatable>()

    /**
     * Register an animatable element for tick updates.
     */
    fun register(element: Animatable) {
        animating.add(element)
    }

    /**
     * Unregister an element (e.g., when destroyed).
     */
    fun unregister(element: Animatable) {
        animating.remove(element)
    }

    /**
     * Process all animations. Call once per server tick.
     */
    fun tick() {
        val iterator = animating.iterator()
        while (iterator.hasNext()) {
            val element = iterator.next()

            // Update spring animations
            element.updateAnimations()

            // Check if still animating
            if (!element.hasActiveAnimations()) {
                iterator.remove()
            } else {
                // Apply animated values to the element
                element.applyAnimatedValues()
            }
        }
    }

    /**
     * Get the count of currently animating elements.
     */
    fun activeCount(): Int = animating.size

    /**
     * Clear all registered elements (e.g., on shutdown).
     */
    fun clear() {
        animating.clear()
    }
}

/**
 * Interface for elements that can be animated.
 */
interface Animatable {
    /**
     * Check if any animations are currently active.
     */
    fun hasActiveAnimations(): Boolean

    /**
     * Update any spring or physics-based animations.
     * Called before applyAnimatedValues.
     */
    fun updateAnimations() {}

    /**
     * Apply current animated values to the element's visual state.
     * This should update displays, positions, colors, etc.
     */
    fun applyAnimatedValues()
}

/**
 * Convenience class that bundles common animated properties.
 */
class AnimatedProperties {
    /** Opacity from 0.0 (transparent) to 1.0 (opaque) */
    val alpha = AnimatedValue.ofFloat(1f)

    /** X translation in local UI units */
    val translateX = AnimatedValue.ofFloat(0f)

    /** Y translation in local UI units */
    val translateY = AnimatedValue.ofFloat(0f)

    /** Z translation (depth) in local UI units */
    val translateZ = AnimatedValue.ofFloat(0f)

    /** Uniform scale factor (1.0 = normal size) */
    val scale = AnimatedValue.ofFloat(1f)

    /** Rotation in degrees */
    val rotation = AnimatedValue.ofFloat(0f)

    /**
     * Check if any property is currently animating.
     */
    fun hasActiveAnimations(): Boolean {
        return alpha.isAnimating ||
                translateX.isAnimating ||
                translateY.isAnimating ||
                translateZ.isAnimating ||
                scale.isAnimating ||
                rotation.isAnimating
    }

    /**
     * Configure all properties with the same duration.
     */
    fun setDuration(duration: Long) {
        alpha.duration = duration
        translateX.duration = duration
        translateY.duration = duration
        translateZ.duration = duration
        scale.duration = duration
        rotation.duration = duration
    }

    /**
     * Configure all properties with the same easing.
     */
    fun setEasing(easing: Easing) {
        alpha.easing = easing
        translateX.easing = easing
        translateY.easing = easing
        translateZ.easing = easing
        scale.easing = easing
        rotation.easing = easing
    }

    // ---- Convenience animation methods ----

    /**
     * Fade in from transparent.
     */
    fun fadeIn(duration: Long = 200L, easing: Easing = Easing.EASE_OUT_CUBIC) {
        alpha.set(0f)
        alpha.duration = duration
        alpha.easing = easing
        alpha.target = 1f
    }

    /**
     * Fade out to transparent.
     */
    fun fadeOut(duration: Long = 200L, easing: Easing = Easing.EASE_OUT_CUBIC) {
        alpha.duration = duration
        alpha.easing = easing
        alpha.target = 0f
    }

    /**
     * Slide in from a direction.
     */
    fun slideIn(
        from: Direction,
        distance: Float = 0.5f,
        duration: Long = 250L,
        easing: Easing = Easing.EASE_OUT_CUBIC
    ) {
        translateX.duration = duration
        translateY.duration = duration
        translateX.easing = easing
        translateY.easing = easing

        when (from) {
            Direction.LEFT -> {
                translateX.set(-distance)
                translateX.target = 0f
            }
            Direction.RIGHT -> {
                translateX.set(distance)
                translateX.target = 0f
            }
            Direction.UP -> {
                translateY.set(distance)
                translateY.target = 0f
            }
            Direction.DOWN -> {
                translateY.set(-distance)
                translateY.target = 0f
            }
        }
    }

    /**
     * Slide out to a direction.
     */
    fun slideOut(
        to: Direction,
        distance: Float = 0.5f,
        duration: Long = 250L,
        easing: Easing = Easing.EASE_IN_CUBIC
    ) {
        translateX.duration = duration
        translateY.duration = duration
        translateX.easing = easing
        translateY.easing = easing

        when (to) {
            Direction.LEFT -> translateX.target = -distance
            Direction.RIGHT -> translateX.target = distance
            Direction.UP -> translateY.target = distance
            Direction.DOWN -> translateY.target = -distance
        }
    }

    /**
     * Scale pop effect (grow then shrink back).
     */
    fun pop(
        targetScale: Float = 1.1f,
        duration: Long = 150L
    ) {
        scale.duration = duration
        scale.easing = Easing.EASE_OUT_BACK
        scale.target = targetScale

        // Schedule return to normal (simplified - in practice use a callback)
        // The caller should handle the return animation
    }

    /**
     * Pulse effect for emphasis.
     */
    fun pulse(
        intensity: Float = 1.05f,
        duration: Long = 150L
    ) {
        scale.duration = duration / 2
        scale.easing = Easing.EASE_OUT_QUAD
        scale.target = intensity
    }

    /**
     * Snap all values to their targets immediately.
     */
    fun snapAll() {
        alpha.snap()
        translateX.snap()
        translateY.snap()
        translateZ.snap()
        scale.snap()
        rotation.snap()
    }
}

/**
 * Direction for slide animations.
 */
enum class Direction {
    LEFT, RIGHT, UP, DOWN
}
