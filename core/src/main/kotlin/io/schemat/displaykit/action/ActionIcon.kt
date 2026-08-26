package io.schemat.displaykit.action

import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.surface.ItemRef
import io.schemat.displaykit.sprite.SpriteId

/**
 * Renderer-neutral icon intent for an [ActionSpec].
 *
 * Renderers select the first representation they support. A vanilla inventory
 * toolbar can render [Item] and [Block]; a surface toolbar can render [Sprite]
 * and fall back recursively when the generated sprite pack is unavailable.
 */
sealed interface ActionIcon {
    data object Default : ActionIcon

    data class Item(val item: ItemRef) : ActionIcon

    data class Block(val state: BlockStateRef) : ActionIcon

    data class Sprite(
        val id: SpriteId,
        val fallback: ActionIcon = Default
    ) : ActionIcon

    /** A compact textual glyph for renderers with no image representation. */
    data class Text(
        val glyph: String,
        val fallback: ActionIcon = Default
    ) : ActionIcon {
        init {
            require(glyph.isNotEmpty()) { "An action text icon cannot be empty." }
        }
    }
}
