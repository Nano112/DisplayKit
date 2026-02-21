package io.schemat.displaykit.render

enum class Billboard {
    FIXED,
    VERTICAL,
    HORIZONTAL,
    CENTER
}

enum class TextAlignment {
    CENTER,
    LEFT,
    RIGHT
}

data class Brightness(
    val block: Int,
    val sky: Int
) {
    companion object {
        val FULL = Brightness(15, 15)
    }
}
