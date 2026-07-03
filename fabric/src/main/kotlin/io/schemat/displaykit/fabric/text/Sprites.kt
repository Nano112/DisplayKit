package io.schemat.displaykit.fabric.text

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.contents.objects.AtlasSprite
import net.minecraft.resources.Identifier

/**
 * Atlas-sprite text components (Minecraft 1.21.9+ `object` content type) and
 * consistent input badges.
 *
 * Sprites render as 8×8 squares inline with text and ignore bold/italic.
 * They work anywhere a [Component] renders: chat, action bar, item names,
 * lore, titles, scoreboard lines.
 *
 * Vanilla ships NO mouse-button sprites, so click affordances use compact
 * text badges ([rmb]/[lmb]/[scroll]) — one visual language everywhere.
 * Factories return fresh components each call (components carry mutable
 * style; never share instances).
 */
object Sprites {

    private val GUI: Identifier = Identifier.withDefaultNamespace("gui")

    /** A sprite from any texture atlas, e.g. `atlas(blocks, block/emerald_block)`. */
    fun atlas(atlas: Identifier, sprite: Identifier): MutableComponent =
        Component.`object`(AtlasSprite(atlas, sprite))

    /** A sprite from the GUI atlas (`textures/gui/sprites/<path>.png`). */
    fun gui(path: String): MutableComponent =
        atlas(GUI, Identifier.withDefaultNamespace(path))

    // ── Useful vanilla GUI sprites (verified present in 1.21.11) ────────────

    fun pageForward(): MutableComponent = gui("recipe_book/page_forward")
    fun pageBackward(): MutableComponent = gui("recipe_book/page_backward")
    fun cross(): MutableComponent = gui("widget/cross_button")

    /** Mob effect icons ride in the GUI atlas under the mob_effect/ prefix. */
    fun mobEffect(id: String): MutableComponent = gui("mob_effect/$id")

    // ── Input badges (no mouse sprites exist in vanilla atlases) ────────────

    fun rmb(): MutableComponent = badge("RMB")
    fun lmb(): MutableComponent = badge("LMB")
    fun scroll(): MutableComponent = badge("SCROLL")

    private fun badge(label: String): MutableComponent =
        Component.literal("[$label]").withStyle {
            it.withColor(ChatFormatting.GRAY).withItalic(false)
        }
}
