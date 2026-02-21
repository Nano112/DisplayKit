package io.schemat.displaykit.render

/**
 * Drop shadow configuration for UI elements.
 *
 * Shadows are rendered as a separate display behind the main element,
 * offset and scaled to create the shadow effect. The blur is approximated
 * by using a semi-transparent color with soft edges.
 *
 * @param offsetX Horizontal offset in UI units (positive = right)
 * @param offsetY Vertical offset in UI units (positive = down/below)
 * @param offsetZ Depth offset in UI units (positive = behind)
 * @param blur Blur radius - affects opacity gradient (larger = softer)
 * @param spread How much larger the shadow is than the element
 * @param color Shadow color (typically semi-transparent black)
 */
data class Shadow(
    val offsetX: Float = 0f,
    val offsetY: Float = -0.02f,
    val offsetZ: Float = 0.01f,
    val blur: Float = 0.05f,
    val spread: Float = 0.02f,
    val color: DkColor = DkColor.BLACK.withAlpha(64)
) {
    /**
     * Calculate the effective alpha based on blur.
     * More blur = more transparency for softer edges.
     */
    val effectiveAlpha: Int
        get() {
            val blurFactor = (1f - (blur * 2f).coerceIn(0f, 0.7f))
            return (color.alpha * blurFactor).toInt().coerceIn(0, 255)
        }

    /**
     * Scale multiplier for the shadow display.
     */
    val scaleMultiplier: Float
        get() = 1f + spread * 2f

    /**
     * Create a new shadow with adjusted opacity.
     */
    fun withAlpha(alpha: Int): Shadow = copy(color = color.withAlpha(alpha))

    /**
     * Create a new shadow with adjusted color.
     */
    fun withColor(newColor: DkColor): Shadow = copy(color = newColor)

    /**
     * Create a variant with the shadow behind (positive Z offset).
     */
    fun behind(distance: Float = 0.01f): Shadow = copy(offsetZ = distance)

    companion object {
        /** No shadow */
        val NONE: Shadow? = null
    }
}

/**
 * Pre-defined shadow presets for common use cases.
 */
object Shadows {
    /** Subtle shadow for slight elevation */
    val SUBTLE = Shadow(
        offsetX = 0f,
        offsetY = -0.01f,
        offsetZ = 0.005f,
        blur = 0.02f,
        spread = 0.01f,
        color = DkColor.BLACK.withAlpha(32)
    )

    /** Small shadow for cards and buttons */
    val SMALL = Shadow(
        offsetX = 0f,
        offsetY = -0.02f,
        offsetZ = 0.008f,
        blur = 0.03f,
        spread = 0.015f,
        color = DkColor.BLACK.withAlpha(48)
    )

    /** Medium shadow for elevated panels */
    val MEDIUM = Shadow(
        offsetX = 0f,
        offsetY = -0.03f,
        offsetZ = 0.01f,
        blur = 0.05f,
        spread = 0.02f,
        color = DkColor.BLACK.withAlpha(64)
    )

    /** Large shadow for modal dialogs */
    val LARGE = Shadow(
        offsetX = 0f,
        offsetY = -0.05f,
        offsetZ = 0.015f,
        blur = 0.08f,
        spread = 0.03f,
        color = DkColor.BLACK.withAlpha(80)
    )

    /** Extra large shadow for popups and menus */
    val XL = Shadow(
        offsetX = 0f,
        offsetY = -0.08f,
        offsetZ = 0.02f,
        blur = 0.12f,
        spread = 0.04f,
        color = DkColor.BLACK.withAlpha(96)
    )

    /** Glow effect - colored shadow with no offset */
    fun glow(
        color: DkColor = DkColor(255, 99, 102, 241), // Indigo
        intensity: Float = 0.5f
    ) = Shadow(
        offsetX = 0f,
        offsetY = 0f,
        offsetZ = 0.01f,
        blur = 0.1f * intensity,
        spread = 0.05f * intensity,
        color = color.withAlpha((128 * intensity).toInt().coerceIn(0, 255))
    )

    /** Success glow (green) */
    val GLOW_SUCCESS = glow(DkColor.fromRGB(34, 197, 94), 0.6f)

    /** Warning glow (yellow) */
    val GLOW_WARNING = glow(DkColor.fromRGB(250, 204, 21), 0.6f)

    /** Error glow (red) */
    val GLOW_ERROR = glow(DkColor.fromRGB(239, 68, 68), 0.6f)

    /** Primary color glow */
    val GLOW_PRIMARY = glow(DkColor.fromRGB(99, 102, 241), 0.6f)

    /** Inner shadow (appears inside the element) */
    val INNER = Shadow(
        offsetX = 0f,
        offsetY = 0.01f,
        offsetZ = -0.005f,
        blur = 0.02f,
        spread = -0.01f,
        color = DkColor.BLACK.withAlpha(48)
    )

    /**
     * Create a custom colored shadow.
     */
    fun colored(
        baseColor: DkColor,
        size: ShadowSize = ShadowSize.MEDIUM
    ): Shadow {
        val base = when (size) {
            ShadowSize.SMALL -> SMALL
            ShadowSize.MEDIUM -> MEDIUM
            ShadowSize.LARGE -> LARGE
        }
        return base.copy(color = baseColor.withAlpha(base.color.alpha))
    }
}

/**
 * Shadow size presets for the colored() helper.
 */
enum class ShadowSize {
    SMALL, MEDIUM, LARGE
}
