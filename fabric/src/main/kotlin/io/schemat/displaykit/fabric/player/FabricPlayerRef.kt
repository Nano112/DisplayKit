package io.schemat.displaykit.fabric.player

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextComponent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.chat.contents.objects.AtlasSprite
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

class FabricPlayerRef(
    val serverPlayer: ServerPlayer
) : PlayerRef {

    override val uuid: UUID
        get() = serverPlayer.uuid

    override val name: String
        get() = serverPlayer.scoreboardName

    override fun eyePosition(): Vec3d {
        return Vec3d(serverPlayer.x, serverPlayer.eyeY, serverPlayer.z)
    }

    override fun lookDirection(): Vec3d {
        val rot = serverPlayer.lookAngle
        return Vec3d(rot.x, rot.y, rot.z)
    }

    override fun isOnline(): Boolean {
        return !serverPlayer.hasDisconnected()
    }

    override fun sendMessage(message: TextComponent) {
        serverPlayer.sendSystemMessage(toMinecraftText(message))
    }

    companion object {
        /**
         * Convert a DisplayKit TextComponent to a Minecraft Component.
         * Supports:
         * - Plain text
         * - Atlas sprites (1.21.5+)
         * - Custom icons via font providers
         * - All standard formatting (color, bold, italic, etc.)
         */
        fun toMinecraftText(component: TextComponent): Component {
            // Store in local vals to enable smart casting
            val sprite = component.sprite
            val icon = component.icon

            // Built once, threaded into createSpriteComponent AND applied again
            // below -- see that function's KDoc for why a sprite content
            // component gets its style set at construction, not only by the
            // later blanket assignment every other branch already relied on.
            val style = buildStyle(component)

            val mcComponent: MutableComponent = when {
                // Atlas sprite content (1.21.5+)
                sprite != null -> {
                    createSpriteComponent(sprite.atlas, sprite.name, style)
                }

                // Custom icon content
                icon != null -> {
                    Component.literal(icon.asString())
                }

                // Plain text content
                else -> {
                    Component.literal(component.text)
                }
            }

            // Apply styling. Redundant with createSpriteComponent's own
            // assignment for the sprite branch (same `style` value, so
            // idempotent) -- kept unconditional because the icon/text
            // branches above still need it and a branch-specific `if` here
            // would be one more place this could silently drift again.
            mcComponent.style = style

            // Append children
            for (child in component.children) {
                mcComponent.append(toMinecraftText(child))
            }

            return mcComponent
        }

        /**
         * Build a Minecraft Style from TextComponent properties.
         */
        private fun buildStyle(component: TextComponent): Style {
            var style = Style.EMPTY

            // Color
            val color = component.color
            if (color != null) {
                style = style.withColor(TextColor.fromRgb(
                    (color.red shl 16) or (color.green shl 8) or color.blue
                ))
            }

            // Font (for icons or custom fonts)
            val fontId = component.font ?: component.icon?.font
            if (fontId != null) {
                val fontIdentifier = Identifier.tryParse(fontId)
                if (fontIdentifier != null) {
                    style = applyFont(style, fontIdentifier)
                }
            }

            // Text decorations
            if (component.bold) style = style.withBold(true)
            if (component.italic) style = style.withItalic(true)
            if (component.underlined) style = style.withUnderlined(true)
            if (component.strikethrough) style = style.withStrikethrough(true)

            return style
        }

        /**
         * Apply a font to a style.
         *
         * MC 1.21.11's `Style.withFont` takes a `FontDescription`, not an `Identifier`.
         * `FontDescription` is an interface; a plain resource-pack font is the
         * `FontDescription.Resource(Identifier)` record.
         */
        private fun applyFont(style: Style, fontId: Identifier): Style {
            return style.withFont(FontDescription.Resource(fontId))
        }

        /**
         * Create a Component for an atlas sprite, with [style] (colour, in
         * particular) applied AT CONSTRUCTION rather than left for a later
         * `.style =` assignment on the caller's side.
         *
         * `RenderMode.ENTITIES` tints sprites -- a solid-colour fill or a
         * frame's background -- via exactly this path
         * (`Surface.spriteEntity`/`TextComponent.withColor`), and every one of
         * them rendered as the untinted white sprite until this changed:
         * `toMinecraftText`'s caller already did `mcComponent.style = style`
         * unconditionally right after this returned, which is the same
         * colour, so that alone was not the fix -- setting it here too, the
         * same way [simpleText] a few lines below already does for plain
         * text, is what actually corrected it. The client DOES honour colour
         * on atlas glyphs (`AtlasGlyphProvider$Instance.renderSprite` calls
         * `setColor` with the style's colour), so applying it here rather
         * than relying solely on the caller closes whatever gap existed
         * between an `object`-content component's style and its
         * construction-time state.
         *
         * In 1.21.9+, atlas sprites can be displayed using Component.object(AtlasSprite).
         * Sprites render as 8x8 pixel squares.
         */
        private fun createSpriteComponent(atlas: String, spriteName: String, style: Style = Style.EMPTY): MutableComponent {
            val atlasId = Identifier.tryParse(atlas) ?: Identifier.fromNamespaceAndPath("minecraft", "gui")
            val spriteId = Identifier.tryParse(spriteName) ?: return Component.literal("[$spriteName]")

            val component = Component.`object`(AtlasSprite(atlasId, spriteId))
            component.style = style
            return component
        }

        /**
         * Helper to create a component with just text and color.
         */
        fun simpleText(text: String, color: DkColor? = null): Component {
            val component = Component.literal(text)
            if (color != null) {
                component.style = Style.EMPTY.withColor(TextColor.fromRgb(
                    (color.red shl 16) or (color.green shl 8) or color.blue
                ))
            }
            return component
        }
    }
}
