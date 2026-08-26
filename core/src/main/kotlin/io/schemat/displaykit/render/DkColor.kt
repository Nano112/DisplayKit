package io.schemat.displaykit.render

data class DkColor(
    val alpha: Int = 255,
    val red: Int = 0,
    val green: Int = 0,
    val blue: Int = 0
) {
    fun toARGB(): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    fun withAlpha(alpha: Int) = copy(alpha = alpha)

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
