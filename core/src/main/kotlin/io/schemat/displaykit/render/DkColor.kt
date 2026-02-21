package io.schemat.displaykit.render

data class DkColor(
    val alpha: Int = 255,
    val red: Int = 0,
    val green: Int = 0,
    val blue: Int = 0
) {
    fun toARGB(): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    fun withAlpha(alpha: Int) = copy(alpha = alpha)

    /**
     * Encode rounded corners into the alpha channel.
     * Alpha values 200-249 signal corner radius to our custom shaders.
     * The radius (0-49) controls corner rounding intensity.
     */
    fun withCornerRadius(radius: Int = 20): DkColor {
        val encodedAlpha = (200 + radius.coerceIn(0, 49))
        return copy(alpha = encodedAlpha)
    }

    /**
     * Encode glassmorphism effect into the alpha channel.
     * Alpha values 100-149 signal glass effect to our custom shaders.
     * The blur level (0-49) controls blur intensity.
     */
    fun withGlass(blurLevel: Int = 25): DkColor {
        val encodedAlpha = (100 + blurLevel.coerceIn(0, 49))
        return copy(alpha = encodedAlpha)
    }

    companion object {
        val WHITE = DkColor(255, 255, 255, 255)
        val BLACK = DkColor(255, 0, 0, 0)
        val RED = DkColor(255, 255, 0, 0)
        val GREEN = DkColor(255, 0, 255, 0)
        val BLUE = DkColor(255, 0, 0, 255)
        val YELLOW = DkColor(255, 255, 255, 0)
        val GRAY = DkColor(255, 128, 128, 128)
        val LIGHT_GRAY = DkColor(255, 192, 192, 192)
        val DARK_GRAY = DkColor(255, 64, 64, 64)
        val TRANSPARENT = DkColor(0, 0, 0, 0)

        fun fromARGB(argb: Int) = DkColor(
            alpha = (argb shr 24) and 0xFF,
            red = (argb shr 16) and 0xFF,
            green = (argb shr 8) and 0xFF,
            blue = argb and 0xFF
        )

        fun fromRGB(red: Int, green: Int, blue: Int) = DkColor(255, red, green, blue)
    }
}
