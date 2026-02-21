package io.schemat.displaykit.fabric.player

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextComponent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
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
        fun toMinecraftText(component: TextComponent): Component {
            val text: MutableComponent = Component.literal(component.text)
            val style = Style.EMPTY.let { s ->
                var result = s
                val color = component.color
                if (color != null) {
                    result = result.withColor(TextColor.fromRgb(
                        (color.red shl 16) or (color.green shl 8) or color.blue
                    ))
                }
                if (component.bold) result = result.withBold(true)
                if (component.italic) result = result.withItalic(true)
                if (component.underlined) result = result.withUnderlined(true)
                if (component.strikethrough) result = result.withStrikethrough(true)
                result
            }
            text.style = style
            for (child in component.children) {
                text.append(toMinecraftText(child))
            }
            return text
        }
    }
}
