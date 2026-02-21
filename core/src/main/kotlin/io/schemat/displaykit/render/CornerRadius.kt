package io.schemat.displaykit.render

/**
 * Corner radius presets for rounded UI elements.
 *
 * These values are encoded in the alpha channel of the background color
 * to signal the shader to apply SDF-based rounded corners.
 *
 * Encoding scheme:
 * - Alpha 200-249: Rounded corners enabled
 * - Radius = (alpha - 200) / 49.0 (0.0 to 1.0 normalized)
 * - Alpha 250+: Full opacity, no rounding
 * - Alpha < 200: Standard transparency or other effects
 */
enum class CornerRadius(
    /** The encoded alpha value (0-255) */
    val encodedAlpha: Int
) {
    /** No rounding - standard square corners */
    NONE(255),

    /** Extra small radius (~2px equivalent) */
    XS(205),

    /** Small radius (~4px equivalent) */
    SM(210),

    /** Medium radius (~8px equivalent) - default */
    MD(220),

    /** Large radius (~12px equivalent) */
    LG(230),

    /** Extra large radius (~16px equivalent) */
    XL(240),

    /** Maximum rounding - pill/capsule shape */
    FULL(249);

    /**
     * Get the normalized radius value (0.0 to 1.0).
     */
    val normalizedRadius: Float
        get() = if (this == NONE) 0f else (encodedAlpha - 200) / 49f

    companion object {
        /**
         * Create a custom corner radius.
         * @param normalized Radius from 0.0 (none) to 1.0 (maximum/pill)
         * @return The encoded alpha value
         */
        fun custom(normalized: Float): Int {
            val clamped = normalized.coerceIn(0f, 1f)
            return if (clamped == 0f) 255 else (200 + (clamped * 49).toInt()).coerceIn(200, 249)
        }

        /**
         * Decode an alpha value to a normalized radius.
         * @return The normalized radius (0.0 to 1.0), or null if not a rounded corner encoding
         */
        fun decodeRadius(alpha: Int): Float? {
            return if (alpha in 200..249) {
                (alpha - 200) / 49f
            } else {
                null
            }
        }
    }
}

/**
 * Extension to create a DkColor with a specific corner radius.
 */
fun DkColor.withCornerRadius(radius: CornerRadius): DkColor {
    return this.copy(alpha = radius.encodedAlpha)
}

/**
 * Extension to create a DkColor with a custom corner radius.
 * @param normalizedRadius Radius from 0.0 (none) to 1.0 (maximum/pill)
 */
fun DkColor.withCornerRadius(normalizedRadius: Float): DkColor {
    return this.copy(alpha = CornerRadius.custom(normalizedRadius))
}
