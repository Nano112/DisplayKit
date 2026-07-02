package io.schemat.displaykit.region

import io.schemat.displaykit.animation.Easing
import io.schemat.displaykit.math.Vec3d

/**
 * Platform-agnostic model for multi-component animations.
 *
 * Each component is an independently-moving [RegionAnimation] with a name.
 * The composite animation orchestrates them together.
 *
 * @param name Optional name for this composite animation
 * @param components The animation components
 */
class CompositeAnimation(
    val name: String? = null,
    val components: List<AnimationComponent>
) {
    init {
        require(components.isNotEmpty()) { "CompositeAnimation must have at least one component" }
    }

    /** Maximum duration across all components. */
    val durationTicks: Int get() = components.maxOf { it.animation.durationTicks }

    /** Number of components. */
    val componentCount: Int get() = components.size

    /** Get a component by name, or null if not found. */
    operator fun get(name: String): AnimationComponent? =
        components.find { it.name == name }

    companion object {
        fun builder(name: String? = null) = Builder(name)
    }

    class Builder(private val name: String?) {
        private val components = mutableListOf<AnimationComponent>()

        /** Add a component with a pre-built [RegionAnimation]. */
        fun component(name: String, animation: RegionAnimation): Builder {
            components.add(AnimationComponent(name, animation))
            return this
        }

        /** Add a simple A-to-B component. */
        fun component(
            name: String,
            capture: RegionCapture,
            from: Vec3d,
            to: Vec3d,
            durationTicks: Int,
            easing: Easing = Easing.EASE_IN_OUT_CUBIC
        ): Builder {
            val animation = RegionAnimation.linear(capture, from, to, durationTicks, easing)
            components.add(AnimationComponent(name, animation))
            return this
        }

        /** Add a component with full keyframe control. */
        fun component(
            name: String,
            capture: RegionCapture,
            keyframes: List<Keyframe>,
            durationTicks: Int
        ): Builder {
            val animation = RegionAnimation(capture, keyframes, durationTicks)
            components.add(AnimationComponent(name, animation))
            return this
        }

        fun build(): CompositeAnimation {
            return CompositeAnimation(name, components.toList())
        }
    }
}
