package io.schemat.displaykit.render

data class TextComponent(
    val text: String = "",
    val color: DkColor? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underlined: Boolean = false,
    val strikethrough: Boolean = false,
    val children: List<TextComponent> = emptyList()
) {
    operator fun plus(other: TextComponent) = copy(children = children + other)

    fun plain(): String = text + children.joinToString("") { it.plain() }

    companion object {
        val EMPTY = TextComponent()

        fun of(text: String) = TextComponent(text = text)
        fun colored(text: String, color: DkColor) = TextComponent(text = text, color = color)
    }
}
