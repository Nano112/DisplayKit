package io.schemat.displaykit.showcase

import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * `/dk demo frame` — reports the nine-slice crops a resizable frame is built
 * from, proving the committed crops tile the sprite exactly.
 *
 * Rendering a live resizable window is an L3 consumer and out of scope; this
 * demo verifies the primitive the consumer will sit on. It reports crop
 * geometry from the committed manifest, which is generated at build time so
 * a dedicated server never needs a runtime source image.
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

        val crops = io.schemat.displaykit.pack.SliceCatalog.regionsFor(button.id)
        if (crops == null) {
            player.sendSystemMessage(Component.literal("widget/button has no committed slices"))
            return
        }
        player.sendSystemMessage(Component.literal("${button.id}: ${button.width}x${button.height}"))
        for (c in crops) {
            player.sendSystemMessage(Component.literal("  crop ${c.x},${c.y} ${c.w}x${c.h}"))
        }
        val area = crops.sumOf { it.w * it.h }
        player.sendSystemMessage(
            Component.literal("Crops tile the sprite exactly: $area == ${button.width * button.height}")
        )
        player.sendSystemMessage(
            Component.literal("${io.schemat.displaykit.pack.SliceCatalog.all().size} sprites carry nine-slice metadata")
        )
    }
}
