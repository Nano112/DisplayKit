package io.schemat.displaykit.velocity.text

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextComponent
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.`object`.ObjectContents

/**
 * Converts DisplayKit TextComponents to Adventure components.
 *
 * Mirrors the Fabric platform's toMinecraftText: plain text, atlas sprites
 * as 1.21.9 object contents, custom icons as their font glyph, and standard
 * formatting. As on Fabric, the style is applied at construction for every
 * branch, sprites included, because atlas glyphs DO honour the style colour
 * and applying it only after the fact is what once shipped every tinted fill
 * as an untinted white sprite.
 */
object AdventureText {

    fun toAdventure(component: TextComponent): Component {
        val sprite = component.sprite
        val icon = component.icon

        val style = buildStyle(component)

        var built: Component = when {
            sprite != null -> spriteComponent(sprite.atlas, sprite.name, style)
            icon != null -> Component.text(icon.asString()).style(style)
            else -> Component.text(component.text).style(style)
        }

        for (child in component.children) {
            built = built.append(toAdventure(child))
        }

        return built
    }

    private fun buildStyle(component: TextComponent): Style {
        var style = Style.empty()

        val color = component.color
        if (color != null) {
            style = style.color(TextColor.color(rgb(color)))
        }

        val fontId = component.font ?: component.icon?.font
        if (fontId != null) {
            parseKey(fontId)?.let { style = style.font(it) }
        }

        if (component.bold) style = style.decorate(TextDecoration.BOLD)
        if (component.italic) style = style.decorate(TextDecoration.ITALIC)
        if (component.underlined) style = style.decorate(TextDecoration.UNDERLINED)
        if (component.strikethrough) style = style.decorate(TextDecoration.STRIKETHROUGH)

        return style
    }

    private fun spriteComponent(atlas: String, spriteName: String, style: Style): Component {
        val atlasKey = parseKey(atlas) ?: Key.key("minecraft", "gui")
        val spriteKey = parseKey(spriteName) ?: return Component.text("[$spriteName]").style(style)

        return Component.`object`(ObjectContents.sprite(atlasKey, spriteKey)).style(style)
    }

    fun simpleText(text: String, color: DkColor? = null): Component {
        val component = Component.text(text)
        return if (color != null) component.color(TextColor.color(rgb(color))) else component
    }

    private fun rgb(color: DkColor): Int =
        (color.red shl 16) or (color.green shl 8) or color.blue

    private fun parseKey(id: String): Key? = try {
        if (id.contains(':')) {
            val (namespace, value) = id.split(':', limit = 2)
            Key.key(namespace, value)
        } else {
            Key.key("minecraft", id)
        }
    } catch (_: Exception) {
        null
    }
}
