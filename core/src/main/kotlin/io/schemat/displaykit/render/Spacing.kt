package io.schemat.displaykit.render

/**
 * Spacing utilities for text layout using custom font providers.
 *
 * Provides positive and negative space characters that can be used
 * to precisely position text elements relative to each other.
 *
 * Usage:
 * ```
 * val text = Spacing.neg(8) + TextComponent.of("Hello") + Spacing.pos(4) + TextComponent.of("World")
 * ```
 *
 * IMPORTANT: Requires SpriteAssetProvider to be registered and the resource pack loaded.
 * For simple overlapping, prefer using multiple TextDisplay entities with z-offset instead.
 *
 * To enable:
 * ```
 * FabricPackIntegration.registerAssetProvider(SpriteAssetProvider)
 * ```
 */
object Spacing {
    // Font containing space characters
    private const val SPACING_FONT = "displaykit:spacing"

    // Negative space characters (pull text left)
    private const val NEG_1 = '\uF001'
    private const val NEG_2 = '\uF002'
    private const val NEG_4 = '\uF004'
    private const val NEG_8 = '\uF008'
    private const val NEG_16 = '\uF010'
    private const val NEG_32 = '\uF020'

    // Positive space characters (push text right)
    private const val POS_1 = '\uF101'
    private const val POS_2 = '\uF102'
    private const val POS_4 = '\uF104'
    private const val POS_8 = '\uF108'
    private const val POS_16 = '\uF110'
    private const val POS_32 = '\uF120'

    /**
     * Create a negative space of the specified pixel width.
     * Negative space pulls subsequent text to the left.
     *
     * @param pixels Number of pixels to shift left (positive number)
     * @return TextComponent with spacing characters
     */
    fun neg(pixels: Int): TextComponent {
        return createSpacing(-pixels)
    }

    /**
     * Create a positive space of the specified pixel width.
     *
     * @param pixels Number of pixels to shift right
     * @return TextComponent with spacing characters
     */
    fun pos(pixels: Int): TextComponent {
        return createSpacing(pixels)
    }

    /**
     * Create spacing of the specified pixel width.
     * Negative values shift left, positive values shift right.
     */
    fun createSpacing(pixels: Int): TextComponent {
        val chars = StringBuilder()
        var remaining = pixels

        if (remaining < 0) {
            // Negative spacing
            while (remaining <= -32) {
                chars.append(NEG_32)
                remaining += 32
            }
            while (remaining <= -16) {
                chars.append(NEG_16)
                remaining += 16
            }
            while (remaining <= -8) {
                chars.append(NEG_8)
                remaining += 8
            }
            while (remaining <= -4) {
                chars.append(NEG_4)
                remaining += 4
            }
            while (remaining <= -2) {
                chars.append(NEG_2)
                remaining += 2
            }
            while (remaining <= -1) {
                chars.append(NEG_1)
                remaining += 1
            }
        } else {
            // Positive spacing
            while (remaining >= 32) {
                chars.append(POS_32)
                remaining -= 32
            }
            while (remaining >= 16) {
                chars.append(POS_16)
                remaining -= 16
            }
            while (remaining >= 8) {
                chars.append(POS_8)
                remaining -= 8
            }
            while (remaining >= 4) {
                chars.append(POS_4)
                remaining -= 4
            }
            while (remaining >= 2) {
                chars.append(POS_2)
                remaining -= 2
            }
            while (remaining >= 1) {
                chars.append(POS_1)
                remaining -= 1
            }
        }

        return TextComponent(text = chars.toString(), font = SPACING_FONT)
    }

    // Pre-built common spacing values
    val NEG1 = neg(1)
    val NEG2 = neg(2)
    val NEG4 = neg(4)
    val NEG8 = neg(8)
    val NEG16 = neg(16)
    val NEG32 = neg(32)

    val POS1 = pos(1)
    val POS2 = pos(2)
    val POS4 = pos(4)
    val POS8 = pos(8)
    val POS16 = pos(16)
    val POS32 = pos(32)
}

/**
 * Extension function to add spacing before a text component.
 */
fun TextComponent.shiftLeft(pixels: Int): TextComponent {
    return Spacing.neg(pixels) + this
}

/**
 * Extension function to add spacing after a text component.
 */
fun TextComponent.shiftRight(pixels: Int): TextComponent {
    return this + Spacing.pos(pixels)
}

/**
 * Create a layer break for stacking elements.
 * Elements after this will render on a new layer, preventing overlap issues.
 */
object Layer {
    private const val NEWLAYER = '\uF200'
    private const val SPACING_FONT = "displaykit:spacing"

    /**
     * Create a new layer break.
     * Use between overlapping elements to ensure proper z-ordering.
     */
    fun newLayer(): TextComponent {
        return TextComponent(text = NEWLAYER.toString(), font = SPACING_FONT)
    }
}
