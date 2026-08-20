package io.schemat.displaykit.showcase

import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.pack.SpacingFontProvider
import io.schemat.displaykit.pack.SpriteFontProvider
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.level.ServerPlayer

/**
 * `/dk demo tint` — by-reference glyphs, at true aspect ratio and tinted to an
 * arbitrary 24-bit colour that no stained-glass palette can express.
 */
object TintDemo {

    /** Matrix green, matching HardwiredTheme.PRIMARY. */
    private val PRIMARY = DkColor.fromRGB(0, 255, 136)

    fun register() {
        ShowcaseMod.registerDemo("tint", ::demo)
    }

    private fun demo(player: ServerPlayer) {
        val index = SpriteIndex.bundled

        // A greyscale sprite tints cleanly; a coloured one goes muddy.
        val tintable = index.all().firstOrNull { it.greyscale && it.glyphEligible }
        val coloured = index.get(SpriteId("gui", "hud/hotbar"))

        if (tintable == null || coloured == null) {
            player.sendSystemMessage(Component.literal("Index missing expected sprites"))
            return
        }

        SpriteGlyphs.request(tintable, 0)
        SpriteGlyphs.request(coloured, 0)

        FabricPackIntegration.registerAssetProvider(SpriteFontProvider)
        FabricPackIntegration.registerAssetProvider(SpacingFontProvider)
        FabricPackIntegration.rebuildAndResendToAll()

        val green = Style.EMPTY.withColor(
            TextColor.fromRgb((PRIMARY.red shl 16) or (PRIMARY.green shl 8) or PRIMARY.blue)
        )

        player.sendSystemMessage(
            Component.literal("tintable (greyscale): ")
                .append(Component.literal(SpriteGlyphs.charsFor(tintable, 0)).withStyle(green))
                .append(Component.literal("  <- ${tintable.id}"))
        )
        player.sendSystemMessage(
            Component.literal("true aspect (${coloured.width}x${coloured.height}): ")
                .append(Component.literal(SpriteGlyphs.charsFor(coloured, 0)))
        )
        player.sendSystemMessage(
            Component.literal("Accept the resource pack prompt if you have not already.")
        )
    }
}
