package io.schemat.displaykit.velocity.text

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextComponent
import net.kyori.adventure.text.ObjectComponent
import net.kyori.adventure.text.TextComponent as AdventureTextComponent
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.`object`.SpriteObjectContents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdventureTextTest {

    @Test
    fun `plain text carries color and decorations`() {
        val component = AdventureText.toAdventure(
            TextComponent(
                text = "Français",
                color = DkColor(0xFF, 0xC2, 0x4F, 0x4F),
                bold = true,
                italic = true,
            )
        )

        assertTrue(component is AdventureTextComponent)
        assertEquals("Français", component.content())
        assertEquals(0xC24F4F, component.style().color()!!.value())
        assertTrue(component.style().hasDecoration(TextDecoration.BOLD))
        assertTrue(component.style().hasDecoration(TextDecoration.ITALIC))
    }

    @Test
    fun `sprites become object contents with the style applied at construction`() {
        val component = AdventureText.toAdventure(
            TextComponent.sprite("minecraft:gui", "hud/heart/full")
                .withColor(DkColor(0xFF, 0x11, 0x22, 0x33))
        )

        assertTrue(component is ObjectComponent, "1.21.9 sprite content expected")
        val contents = component.contents()
        assertTrue(contents is SpriteObjectContents)
        assertEquals("minecraft:gui", contents.atlas().asString())
        assertEquals("minecraft:hud/heart/full", contents.sprite().asString())
        assertEquals(0x112233, component.style().color()!!.value(), "tint must survive construction")
    }

    @Test
    fun `children append in order`() {
        val component = AdventureText.toAdventure(
            TextComponent.of("a") + TextComponent.of("b") + TextComponent.of("c")
        )

        assertEquals(2, component.children().size)
        assertEquals("b", (component.children()[0] as AdventureTextComponent).content())
        assertEquals("c", (component.children()[1] as AdventureTextComponent).content())
    }

    @Test
    fun `icons render as their font glyph`() {
        val component = AdventureText.toAdventure(TextComponent.icon("checkbox_checked"))

        assertTrue(component is AdventureTextComponent)
        assertEquals("displaykit:icons", component.style().font()!!.asString())
    }
}
