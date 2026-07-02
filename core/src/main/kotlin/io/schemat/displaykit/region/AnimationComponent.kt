package io.schemat.displaykit.region

/**
 * A named wrapper for a [RegionAnimation] within a composite animation.
 *
 * @param name Unique identifier for this component (e.g. "left_door", "right_door")
 * @param animation The underlying region animation
 */
data class AnimationComponent(
    val name: String,
    val animation: RegionAnimation
)
