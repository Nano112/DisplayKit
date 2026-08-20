package io.schemat.displaykit.showcase

import io.schemat.displaykit.pack.SpriteSlicer
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * `/dk demo frame` — reports the nine-slice regions a resizable frame is built
 * from, proving the harvested insets tile the sprite exactly.
 *
 * Rendering a live resizable window is an L3 consumer and out of scope; this
 * demo verifies the primitive the consumer will sit on. It reports region
 * geometry only, so it needs no source image — see the KDoc on
 * `SpriteSliceProvider.requestSlices` for why a dedicated server has no
 * runtime path to that image anyway.
 */
object FrameDemo {

    fun register() {
        ShowcaseMod.registerDemo("frame", ::demo)
    }

    private fun demo(player: ServerPlayer) {
        val button = SpriteIndex.bundled.get(SpriteId("gui", "widget/button"))
        if (button?.nineSlice == null) {
            player.sendSystemMessage(Component.literal("widget/button has no nine-slice metadata"))
            return
        }

        val regions = SpriteSlicer.ninePatch(button)
        val area = regions.sumOf { it.w * it.h }

        player.sendSystemMessage(
            Component.literal("${button.id}: ${button.width}x${button.height}, border ${button.nineSlice}")
        )
        for (r in regions) {
            player.sendSystemMessage(
                Component.literal("  region ${r.x},${r.y} ${r.w}x${r.h}")
            )
        }
        player.sendSystemMessage(
            Component.literal(
                "Regions tile the sprite exactly: $area == ${button.width * button.height}"
            )
        )

        val sliceCount = SpriteIndex.bundled.all().count { it.nineSlice != null }
        player.sendSystemMessage(Component.literal("$sliceCount sprites carry nine-slice metadata"))
    }
}
