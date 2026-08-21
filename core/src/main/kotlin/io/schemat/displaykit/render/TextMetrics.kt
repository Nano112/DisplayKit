package io.schemat.displaykit.render

/**
 * Approximate metrics for Minecraft's default font, used to reason about
 * text_display geometry in world units.
 *
 * Anchoring model: a text_display renders its text block with the entity
 * origin at the BOTTOM-CENTER of the block (nametag-style — text grows
 * upward from the position). Every element that wants center-anchored text
 * must translate its display down by [verticalCenterCorrection].
 *
 * All constants live here so the anchor model is defined in exactly one place.
 */
object TextMetrics {

    /** World size of one text pixel at transformation scale 1.0. */
    const val PIXEL_SIZE = 0.025f

    /** Line height of the default font, in text pixels. */
    const val LINE_HEIGHT_PX = 10

    /**
     * Minecraft's actual font line height, in text pixels.
     *
     * Verified from the decompiled 1.21.11 client:
     * `net.minecraft.client.gui.Font`'s constructor does `bipush 9; putfield
     * lineHeight`. [LINE_HEIGHT_PX] (10) is a legacy approximation kept only
     * for the block-widget geometry ([blockHeight], [verticalCenterCorrection])
     * that has been tuned by eye against it — changing that constant would
     * shift every existing DisplayKit UI. Anything reasoning about how
     * `SpriteCanvas`/`NineSlicePainter` rows map onto the text component the
     * client actually splits into lines (the compositor) must use this
     * constant instead.
     */
    const val FONT_LINE_HEIGHT_PX = 9

    /**
     * Advance width of a character in the default Minecraft font (text pixels).
     * Approximation table — good enough for layout/hitbox math.
     */
    fun charWidthPx(c: Char): Int = when (c) {
        'i', '!', ',', '.', ':', ';', '|', '\'' -> 2
        'l' -> 3
        't', 'I', '[', ']', ' ', '(', ')', '{', '}', '"' -> 4
        'f', 'k' -> 5
        '@', '~' -> 7
        else -> 6
    }

    /** Widest line of [text], in text pixels. */
    fun textWidthPx(text: String): Int =
        text.split('\n').maxOfOrNull { line -> line.sumOf { charWidthPx(it) } } ?: 0

    fun lineCount(text: String): Int = text.count { it == '\n' } + 1

    /** Rendered width of the text block in world units at [scale]. */
    fun blockWidth(text: String, scale: Float): Float =
        textWidthPx(text) * PIXEL_SIZE * scale

    /** Rendered height of the text block in world units at [scale]. */
    fun blockHeight(text: String, scale: Float): Float =
        lineCount(text) * LINE_HEIGHT_PX * PIXEL_SIZE * scale

    /**
     * Y translation that moves a bottom-anchored text block so its visual
     * center sits on the entity position.
     */
    fun verticalCenterCorrection(text: String, scale: Float): Float =
        -blockHeight(text, scale) / 2f
}
