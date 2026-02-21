package io.schemat.displaykit.render

/**
 * Background styles for UI panels.
 *
 * Each style encodes its type in the alpha channel, allowing the shader
 * to detect and apply the appropriate visual effect.
 *
 * Encoding scheme (alpha ranges):
 * - 250-255: Solid (full opacity, no effects)
 * - 200-249: Rounded corners (radius encoded)
 * - 100-149: Glass effect (blur intensity encoded)
 * - 0-99: Standard transparency
 *
 * @param encodedAlpha The alpha value that signals this style to the shader
 */
enum class BackgroundStyle(
    val encodedAlpha: Int,
    val description: String
) {
    /** Standard solid background - no special effects */
    SOLID(255, "Solid background"),

    /** Subtle frosted glass - light blur, high transparency */
    GLASS_SUBTLE(110, "Subtle frosted glass"),

    /** Standard frosted glass - medium blur */
    GLASS(125, "Frosted glass"),

    /** Heavy frosted glass - strong blur, more opaque */
    GLASS_HEAVY(140, "Heavy frosted glass"),

    /** Acrylic style - very subtle blur with noise texture */
    ACRYLIC(105, "Acrylic/noise texture");

    /**
     * Get the blur intensity (0.0 to 1.0) for glass styles.
     */
    val blurIntensity: Float
        get() = if (encodedAlpha in 100..149) {
            (encodedAlpha - 100) / 49f
        } else {
            0f
        }

    /**
     * Whether this style uses the glass effect.
     */
    val isGlass: Boolean
        get() = encodedAlpha in 100..149

    companion object {
        /**
         * Create a custom glass intensity.
         * @param intensity Blur intensity from 0.0 (subtle) to 1.0 (heavy)
         * @return The encoded alpha value
         */
        fun customGlass(intensity: Float): Int {
            val clamped = intensity.coerceIn(0f, 1f)
            return (100 + (clamped * 49).toInt()).coerceIn(100, 149)
        }

        /**
         * Decode an alpha value to determine the background style.
         * @return The decoded style, or null if it's standard transparency
         */
        fun decode(alpha: Int): BackgroundStyle? {
            return when (alpha) {
                in 250..255 -> SOLID
                in 135..149 -> GLASS_HEAVY
                in 120..134 -> GLASS
                in 110..119 -> GLASS_SUBTLE
                in 100..109 -> ACRYLIC
                else -> null
            }
        }
    }
}

/**
 * Extension to create a DkColor with a specific background style.
 * Note: This will override any corner radius encoding.
 */
fun DkColor.withBackgroundStyle(style: BackgroundStyle): DkColor {
    return this.copy(alpha = style.encodedAlpha)
}

/**
 * Extension to create a DkColor with custom glass intensity.
 * @param intensity Blur intensity from 0.0 (subtle) to 1.0 (heavy)
 */
fun DkColor.withGlass(intensity: Float): DkColor {
    return this.copy(alpha = BackgroundStyle.customGlass(intensity))
}

/**
 * Combines both rounded corners and glass effect.
 *
 * Since the alpha channel can only encode one effect at a time,
 * this uses a different approach: the glass panels need to be
 * rendered in a specific way that the post-processor can detect.
 *
 * For now, this prioritizes the glass effect over rounded corners
 * when both are requested. A future implementation could use
 * additional color channels or a separate marking system.
 */
data class PanelStyle(
    val cornerRadius: CornerRadius = CornerRadius.NONE,
    val background: BackgroundStyle = BackgroundStyle.SOLID,
    val shadow: Shadow? = null
) {
    /**
     * Get the encoded alpha for this style.
     * Glass takes priority over rounded corners if both are set.
     */
    val encodedAlpha: Int
        get() = when {
            background.isGlass -> background.encodedAlpha
            cornerRadius != CornerRadius.NONE -> cornerRadius.encodedAlpha
            else -> background.encodedAlpha
        }

    /**
     * Apply this style to a color.
     */
    fun applyTo(color: DkColor): DkColor {
        return color.copy(alpha = encodedAlpha)
    }

    companion object {
        /** Default solid style with no effects */
        val SOLID = PanelStyle()

        /** Rounded medium corners, solid background */
        val ROUNDED = PanelStyle(cornerRadius = CornerRadius.MD)

        /** Frosted glass with medium corners */
        val GLASS = PanelStyle(
            cornerRadius = CornerRadius.MD,
            background = BackgroundStyle.GLASS
        )

        /** Elevated panel with shadow */
        val ELEVATED = PanelStyle(
            cornerRadius = CornerRadius.MD,
            shadow = Shadows.MEDIUM
        )

        /** Card style - rounded, elevated */
        val CARD = PanelStyle(
            cornerRadius = CornerRadius.LG,
            shadow = Shadows.SMALL
        )

        /** Modal/dialog style - glass with large shadow */
        val MODAL = PanelStyle(
            cornerRadius = CornerRadius.LG,
            background = BackgroundStyle.GLASS,
            shadow = Shadows.XL
        )
    }
}
