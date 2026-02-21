package io.schemat.displaykit.animation

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Easing functions for smooth animations.
 *
 * Each function takes a progress value from 0.0 to 1.0 and returns
 * an eased value, typically also in the 0.0 to 1.0 range (though
 * some like EASE_OUT_BACK can overshoot).
 */
enum class Easing(val apply: (Float) -> Float) {
    /** Linear interpolation - constant speed */
    LINEAR({ it }),

    // Quadratic easing
    EASE_IN_QUAD({ it * it }),
    EASE_OUT_QUAD({ 1f - (1f - it) * (1f - it) }),
    EASE_IN_OUT_QUAD({
        if (it < 0.5f) 2f * it * it
        else 1f - (-2f * it + 2f).pow(2) / 2f
    }),

    // Cubic easing
    EASE_IN_CUBIC({ it * it * it }),
    EASE_OUT_CUBIC({ 1f - (1f - it).pow(3) }),
    EASE_IN_OUT_CUBIC({
        if (it < 0.5f) 4f * it * it * it
        else 1f - (-2f * it + 2f).pow(3) / 2f
    }),

    // Quartic easing
    EASE_IN_QUART({ it * it * it * it }),
    EASE_OUT_QUART({ 1f - (1f - it).pow(4) }),
    EASE_IN_OUT_QUART({
        if (it < 0.5f) 8f * it * it * it * it
        else 1f - (-2f * it + 2f).pow(4) / 2f
    }),

    // Exponential easing
    EASE_IN_EXPO({
        if (it == 0f) 0f else 2f.pow(10f * it - 10f)
    }),
    EASE_OUT_EXPO({
        if (it == 1f) 1f else 1f - 2f.pow(-10f * it)
    }),
    EASE_IN_OUT_EXPO({
        when {
            it == 0f -> 0f
            it == 1f -> 1f
            it < 0.5f -> 2f.pow(20f * it - 10f) / 2f
            else -> (2f - 2f.pow(-20f * it + 10f)) / 2f
        }
    }),

    // Back easing - overshoots then settles
    EASE_IN_BACK({
        val c1 = 1.70158f
        val c3 = c1 + 1f
        c3 * it * it * it - c1 * it * it
    }),
    EASE_OUT_BACK({
        val c1 = 1.70158f
        val c3 = c1 + 1f
        1f + c3 * (it - 1f).pow(3) + c1 * (it - 1f).pow(2)
    }),
    EASE_IN_OUT_BACK({
        val c1 = 1.70158f
        val c2 = c1 * 1.525f
        if (it < 0.5f) {
            ((2f * it).pow(2) * ((c2 + 1f) * 2f * it - c2)) / 2f
        } else {
            ((2f * it - 2f).pow(2) * ((c2 + 1f) * (it * 2f - 2f) + c2) + 2f) / 2f
        }
    }),

    // Elastic easing - bouncy spring effect
    EASE_IN_ELASTIC({
        val c4 = (2f * PI.toFloat()) / 3f
        when {
            it == 0f -> 0f
            it == 1f -> 1f
            else -> -2f.pow(10f * it - 10f) * sin((it * 10f - 10.75f) * c4)
        }
    }),
    EASE_OUT_ELASTIC({
        val c4 = (2f * PI.toFloat()) / 3f
        when {
            it == 0f -> 0f
            it == 1f -> 1f
            else -> 2f.pow(-10f * it) * sin((it * 10f - 0.75f) * c4) + 1f
        }
    }),
    EASE_IN_OUT_ELASTIC({
        val c5 = (2f * PI.toFloat()) / 4.5f
        when {
            it == 0f -> 0f
            it == 1f -> 1f
            it < 0.5f -> -(2f.pow(20f * it - 10f) * sin((20f * it - 11.125f) * c5)) / 2f
            else -> (2f.pow(-20f * it + 10f) * sin((20f * it - 11.125f) * c5)) / 2f + 1f
        }
    }),

    // Bounce easing
    EASE_OUT_BOUNCE({
        val n1 = 7.5625f
        val d1 = 2.75f
        when {
            it < 1f / d1 -> n1 * it * it
            it < 2f / d1 -> {
                val t = it - 1.5f / d1
                n1 * t * t + 0.75f
            }
            it < 2.5f / d1 -> {
                val t = it - 2.25f / d1
                n1 * t * t + 0.9375f
            }
            else -> {
                val t = it - 2.625f / d1
                n1 * t * t + 0.984375f
            }
        }
    }),
    EASE_IN_BOUNCE({
        1f - EASE_OUT_BOUNCE.apply(1f - it)
    }),
    EASE_IN_OUT_BOUNCE({
        if (it < 0.5f) {
            (1f - EASE_OUT_BOUNCE.apply(1f - 2f * it)) / 2f
        } else {
            (1f + EASE_OUT_BOUNCE.apply(2f * it - 1f)) / 2f
        }
    });

    companion object {
        /** Default easing for most UI animations */
        val DEFAULT = EASE_OUT_CUBIC

        /** Quick, snappy animations */
        val SNAPPY = EASE_OUT_QUART

        /** Smooth, gentle transitions */
        val SMOOTH = EASE_IN_OUT_CUBIC

        /** Bouncy, playful animations */
        val BOUNCY = EASE_OUT_BACK
    }
}
