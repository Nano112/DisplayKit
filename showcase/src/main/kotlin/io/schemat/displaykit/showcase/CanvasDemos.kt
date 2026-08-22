package io.schemat.displaykit.showcase

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.fabric.pack.PackSync
import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.Billboard
import io.schemat.displaykit.render.Brightness
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.sprite.SpriteCanvas
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.joml.Matrix4f

/**
 * `/dk demo terminal` and `/dk demo grid` — the compositor render mode.
 */
object CanvasDemos {

    private const val LIFETIME_TICKS = 20L * 60

    // A bare VirtualTextDisplay renders at TextMetrics.PIXEL_SIZE world units
    // per canvas pixel (transformation scale 1) -- far too wide for these
    // demo canvases (320px/400px). Derive a transformation scale from a
    // target on-screen width instead, the same way GridMapTab does.
    private const val TERMINAL_TARGET_WIDTH_BLOCKS = 4f
    private const val GRID_TARGET_WIDTH_BLOCKS = 3f

    private fun scaleFor(canvas: SpriteCanvas, targetWidthBlocks: Float): Float =
        targetWidthBlocks / (canvas.widthPx * TextMetrics.PIXEL_SIZE)

    private fun transformFor(scale: Float): Mat4f =
        Mat4f(Matrix4f().scale(scale, scale, scale))

    // Pinned to the same sprite GridMapTab uses: vanilla ships no small
    // greyscale sprite, and glyph tint is multiplicative, so only a uniform
    // pure-white opaque source reproduces a requested colour exactly.
    // lightning_rod_on is the one vanilla sprite confirmed to fit.
    private val CELL_SPRITE_ID = SpriteId("blocks", "block/lightning_rod_on")

    fun register() {
        ShowcaseMod.registerDemo("terminal", ::demoTerminal)
        ShowcaseMod.registerDemo("grid", ::demoGrid)
    }

    /** A monospace terminal composed of sprite glyphs, in one entity. */
    private fun demoTerminal(player: ServerPlayer) {
        val canvas = SpriteCanvas(widthPx = 320, heightPx = 180)
        val lines = listOf(
            "DisplayKit terminal",
            "composited into ONE text display",
            "x via spacing advances",
            "y via per-glyph ascent"
        )
        // Restrained terminal palette, legible in the vanilla font: matrix
        // green for the prompt line, soft off-white body text, and a couple
        // of muted accents so it reads as a terminal, not a wall of one hue.
        val lineTints = listOf(
            DkColor.fromRGB(0, 255, 136),
            DkColor.fromRGB(214, 219, 214),
            DkColor.fromRGB(45, 212, 191),
            DkColor.fromRGB(250, 204, 21)
        )
        PackSync.withPackSync("canvas-demo") {
            lines.forEachIndexed { row, line ->
                canvas.text(line, x = 0, y = row * 10, tint = lineTints[row % lineTints.size])
            }
        }

        val display = VirtualTextDisplay().apply {
            position = Vec3d(player.x, player.y + 2.0, player.z + 4.0)
            text = canvas.toTextComponent()
            billboard = Billboard.CENTER
            // Alpha 100-149 and 200-249 are DkColor shader sentinels (glass /
            // corner-radius, see DkColor.withGlass / withCornerRadius) -- 190
            // sits outside both. RGB is a dark, neutral graphite -- no green
            // cast -- so the varied line tints above read cleanly against it.
            backgroundColor = DkColor(190, 22, 23, 26)
            brightness = Brightness.FULL
            hasShadow = false
            transformation = transformFor(scaleFor(canvas, TERMINAL_TARGET_WIDTH_BLOCKS))
        }
        spawn(display, player)
        player.sendSystemMessage(Component.literal("Terminal: 1 entity for ${lines.size} lines."))
    }

    /**
     * The GridMapTab comparison: a 25x25 minimap as 625 panel entities beside
     * the same image as a single composited display.
     */
    private fun demoGrid(player: ServerPlayer) {
        val cell = SpriteIndex.bundled.get(CELL_SPRITE_ID)
            ?: run {
                player.sendSystemMessage(Component.literal("No suitable greyscale cell sprite"))
                return
            }

        val canvas = SpriteCanvas(widthPx = 25 * cell.width, heightPx = 25 * cell.height)
        val palette = listOf(
            DkColor.fromRGB(0, 255, 136),
            DkColor.fromRGB(0, 200, 255),
            DkColor.fromRGB(250, 204, 21)
        )
        PackSync.withPackSync("canvas-demo") {
            for (cz in 0 until 25) {
                for (cx in 0 until 25) {
                    canvas.draw(
                        cell,
                        x = cx * cell.width,
                        y = cz * cell.height,
                        tint = palette[(cx + cz) % palette.size]
                    )
                }
            }
        }

        val display = VirtualTextDisplay().apply {
            position = Vec3d(player.x, player.y + 2.0, player.z + 6.0)
            text = canvas.toTextComponent()
            billboard = Billboard.CENTER
            backgroundColor = DkColor.TRANSPARENT
            brightness = Brightness.FULL
            hasShadow = false
            transformation = transformFor(scaleFor(canvas, GRID_TARGET_WIDTH_BLOCKS))
        }
        spawn(display, player)

        player.sendSystemMessage(
            Component.literal(
                "25x25 minimap. GridMapTab spawns 625 entities for this; " +
                    "the compositor uses 1. Glyph variants allocated: " +
                    "${SpriteGlyphs.requested().size}"
            )
        )
    }

    private fun spawn(display: VirtualTextDisplay, player: ServerPlayer) {
        val viewers = setOf(player.uuid)
        DisplayKit.platform.packetSender.spawnEntity(display, viewers)
        DisplayKit.platform.packetSender.updateMetadata(display, viewers)
        DisplayKit.platform.scheduler.scheduleDelayed(LIFETIME_TICKS) {
            DisplayKit.platform.packetSender.destroyEntities(listOf(display.entityId), viewers)
        }
    }
}
