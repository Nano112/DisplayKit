package io.schemat.displaykit.fabric.player

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextComponent
import net.minecraft.network.chat.Component
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

            val mcComponent: MutableComponent = when {
                // Atlas sprite content (1.21.5+)
                sprite != null -> {
                    createSpriteComponent(sprite.atlas, sprite.name)
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

            // Apply styling
            val style = buildStyle(component)
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
         * Apply font to style, handling version differences via reflection.
         * MC 1.21.11+ uses FontDescription instead of direct Identifier.
         */
        private fun applyFont(style: Style, fontId: Identifier): Style {
            return try {
                // Try direct Identifier approach first (older MC versions)
                val method = Style::class.java.getMethod("withFont", Identifier::class.java)
                method.invoke(style, fontId) as Style
            } catch (e: NoSuchMethodException) {
                // Newer versions may use FontDescription or ResourceLocation
                try {
                    val fontDescClass = Class.forName("net.minecraft.network.chat.FontDescription")
                    val ofMethod = fontDescClass.getMethod("of", Identifier::class.java)
                    val fontDesc = ofMethod.invoke(null, fontId)
                    val withFontMethod = Style::class.java.getMethod("withFont", fontDescClass)
                    withFontMethod.invoke(style, fontDesc) as Style
                } catch (e2: Exception) {
                    // Last resort: try ResourceLocation (if different from Identifier)
                    try {
                        val rlClass = Class.forName("net.minecraft.resources.ResourceLocation")
                        val parseMethod = rlClass.getMethod("parse", String::class.java)
                        val rl = parseMethod.invoke(null, fontId.toString())
                        val withFontMethod = Style::class.java.getMethod("withFont", rlClass)
                        withFontMethod.invoke(style, rl) as Style
                    } catch (e3: Exception) {
                        // Fall back to original style without font
                        style
                    }
                }
            } catch (e: Exception) {
                style
            }
        }

        /**
         * Create a Component for an atlas sprite.
         *
         * In 1.21.9+, atlas sprites can be displayed using Component.object(AtlasSprite).
         * Sprites render as 8x8 pixel squares.
         */
        private fun createSpriteComponent(atlas: String, spriteName: String): MutableComponent {
            val atlasId = Identifier.tryParse(atlas) ?: Identifier.fromNamespaceAndPath("minecraft", "gui")
            val spriteId = Identifier.tryParse(spriteName) ?: return Component.literal("[$spriteName]")

            return Component.`object`(AtlasSprite(atlasId, spriteId))
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
