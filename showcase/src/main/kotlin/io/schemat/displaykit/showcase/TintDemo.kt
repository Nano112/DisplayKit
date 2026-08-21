package io.schemat.displaykit.showcase

import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.fabric.text.Sprites
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
 * `/dk demo tint` — settles, by looking, whether vanilla's `AtlasSprite` object
 * component respects a Style's colour, and shows the by-reference glyph tint
 * path (which is known to work) for comparison.
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

        FabricPackIntegration.registerAssetProvider(SpriteFontProvider)
        FabricPackIntegration.registerAssetProvider(SpacingFontProvider)
        FabricPackIntegration.rebuildAndResendToAll()

        val green = Style.EMPTY.withColor(
            TextColor.fromRgb((PRIMARY.red shl 16) or (PRIMARY.green shl 8) or PRIMARY.blue)
        )
        val red = Style.EMPTY.withColor(TextColor.fromRgb(0xFF0000))

        player.sendSystemMessage(
            Component.literal("1. vanilla AtlasSprite, no colour: ")
                .append(Sprites.forEntry(coloured))
        )
        player.sendSystemMessage(
            Component.literal("2. vanilla AtlasSprite, RED style colour: ")
                .append(Sprites.forEntry(coloured).withStyle(red))
        )
        player.sendSystemMessage(
            Component.literal("3. by-reference glyph, no tint: ")
                .append(Component.literal(SpriteGlyphs.charsFor(tintable, 0)))
        )
        player.sendSystemMessage(
            Component.literal("4. by-reference glyph, matrix-green tint: ")
                .append(Component.literal(SpriteGlyphs.charsFor(tintable, 0)).withStyle(green))
        )
        player.sendSystemMessage(
            Component.literal("Line 2 answers it: red means vanilla AtlasSprite honours Style colour; unchanged means it does not.")
        )
    }
}
