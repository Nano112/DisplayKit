package io.schemat.displaykit.showcase

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.fabric.pack.PackSync
import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceHost
import io.schemat.displaykit.surface.SurfacePlacement
import io.schemat.displaykit.ui.InteractionRouter
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A calibration target for measuring where sprites ACTUALLY land.
 *
 * ### Why this exists
 *
 * Chrome has been subtly misaligned in ways that only show up in a
 * screenshot: the on-surface cursor sat 7.9 canvas pixels high, and the
 * terminal's close button hangs about a row below the title bar it is
 * supposed to be centred in. Both are sprites whose native height differs
 * from the renderer's 10px line pitch, which points at one root cause in
 * vertical anchoring rather than two unrelated layout mistakes.
 *
 * Guessing at that from a screenshot has already failed twice, so this
 * renders a pattern designed to be MEASURED instead of eyeballed.
 *
 * ### How it measures
 *
 * There is deliberately no hand-built "reference" mark, because any mark
 * drawn through the same canvas path would carry the same error and the
 * measurement would read zero. Instead the identical pattern renders in both
 * render modes and the two are diffed:
 *
 *  - [RenderMode.ENTITIES] placement is already asserted to agree with
 *    `planePoint` AND with a real raycast (`RenderModeTest`), so it is the
 *    known-good reference.
 *  - [RenderMode.COMPOSITED] is the path under suspicion.
 *
 * With the camera pose held fixed between the two captures, any per-sprite
 * difference in screen row IS the defect, in isolation, with no scale factor
 * or absolute origin to get wrong.
 *
 * Sprites are tinted pure green so a script can find them: glyph tint is
 * multiplicative, so every entry below is greyscale and tints cleanly.
 * Heights span the line pitch from well under it to well over.
 */
object CalibrationWindow {

    /**
     * Canvas gap between the two copies of the sprite under test.
     *
     * Each capture draws the SAME sprite twice, this far apart, so the
     * capture calibrates its own screen-pixels-per-canvas-pixel scale with no
     * dependence on camera distance or field of view. Far larger than any
     * target's height, so the two marks can never merge into one band.
     */
    const val ROW_PITCH = 120

    /** Canvas X the test sprites are drawn at, so a script can scan one band. */
    const val TEST_X = 24

    /** Canvas Y of the first test row. */
    const val FIRST_Y = 24

    private const val W = 220
    private val H: Int get() = FIRST_Y * 2 + ROW_PITCH + 40

    /**
     * Magenta, not green: the world is full of green.
     *
     * A detector loose enough to catch a DARK tinted pixel -- tint is
     * multiplicative, so a dark greyscale pixel tints dark -- also caught
     * every grass block behind the panel. No natural Minecraft surface is
     * magenta, so r-high/b-high/g-low separates the marks from the world at
     * any brightness.
     */
    private val MARK = DkColor(255, 255, 0, 255)

    /**
     * Greyscale, non-nine-slice sprites spanning heights either side of
     * [TextMetrics.FONT_LINE_HEIGHT_PX]. Nine-slice entries are excluded on
     * purpose: `frame` takes a different code path from `icon`, and mixing
     * the two would confound the measurement.
     */
    /**
     * Index selecting the CROSS-LAYER target instead of a sprite: a fill, an
     * icon and a label all asked for the same canvas Y, in three separate
     * x-bands so each can be measured on its own.
     *
     * `fill` paints on the chrome layer, `icon` on the icon layer and `label`
     * on the text layer, and every layer is its own text display. If those
     * entities do not share a vertical origin then everything composed of
     * more than one of them is skewed -- which is what a title bar is: strip,
     * label and close button, one per layer.
     */
    const val LAYERS_INDEX = 99

    val TARGETS: List<SpriteId> = listOf(
        SpriteId("particles", "critical_hit"),                  // 8x8
        SpriteId("gui", "container/cartography_table/locked"),  // 10x14
        SpriteId("gui", "hud/crosshair"),                       // 15x15
        SpriteId("blocks", "block/anvil"),                      // 16x16
        SpriteId("gui", "dialog/warning_button_disabled"),      // 20x20
        SpriteId("gui", "hud/effect_background"),               // 24x24
        SpriteId("particles", "explosion_0")                    // 32x32
    )

    /** Canvas Y the two copies' TOP edges are asked to sit at. */
    fun expectedTops(): Pair<Int, Int> = FIRST_Y to (FIRST_Y + ROW_PITCH)

    /**
     * Far enough that the whole panel, edges included, fits in frame.
     *
     * At 3 blocks the target overflowed the viewport, so its top and bottom
     * edges were off-screen and any measurement AGAINST those edges silently
     * measured the viewport instead. A measurement instrument has to be
     * entirely visible.
     */
    private const val VIEW_DISTANCE = 8.0

    private val open = ConcurrentHashMap<UUID, SurfaceHost>()

    fun open(player: ServerPlayer, mode: RenderMode, targetIndex: Int) {
        closeFor(player.uuid)

        val ref = FabricPlayerRef(player)
        val look = ref.lookDirection()
        val yaw = Surface.yawFacing(look)

        val surface = Surface(W, H, Vec3d.ZERO, targetWidthBlocks = 2.5f)
        surface.renderMode = mode
        surface.yawDegrees = yaw

        val worldW = (surface.widthPx * surface.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        val worldH = (surface.heightPx * surface.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        surface.position = SurfacePlacement.inFrontOf(
            ref.eyePosition(), look, VIEW_DISTANCE, yaw, worldW, worldH
        )
        surface.backdrop = DkColor(255, 8, 8, 10)
        surface.backingBlock = BlockStateRef.BLACK_CONCRETE

        val host = SurfaceHost(DisplayKit.platform, ref, surface)
        open[player.uuid] = host

        val layersMode = targetIndex == LAYERS_INDEX
        val id = TARGETS[targetIndex.coerceIn(TARGETS.indices)]
        val entry: SpriteEntry? = SpriteIndex.bundled.get(id)
        val (topA, topB) = expectedTops()
        val paint = {
            surface.paint {
                if (layersMode) {
                    // All three at the SAME canvas y, in separate x-bands.
                    val y = topA
                    fill(MARK, Rect(16, y, 40, 16))
                    SpriteIndex.bundled.get(SpriteId("gui", "hud/crosshair"))?.let {
                        icon(it, 80, y, MARK)
                    }
                    label("MMMMMM", 120, y, MARK)
                } else if (entry != null) {
                    // ONE sprite per capture, drawn TWICE. Rendering the whole
                    // set at once could not be measured reliably: a sprite
                    // with a hollow centre (the crosshair) splits into several
                    // detected bands, and nothing tells a split sprite apart
                    // from the next sprite down. Two copies of one sprite, far
                    // apart, are unmistakable -- and their known separation
                    // gives the capture its own scale.
                    icon(entry, TEST_X, topA, MARK)
                    icon(entry, TEST_X, topB, MARK)
                }
            }
        }

        if (mode == RenderMode.ENTITIES) {
            paint()
            host.open()
            InteractionRouter.registerSurface(player.uuid, host)
        } else {
            PackSync.withPackSync("calibration") { paint() }
            FabricPackIntegration.whenPackApplied(player.uuid) {
                if (open[player.uuid] !== host) return@whenPackApplied
                host.open()
                InteractionRouter.registerSurface(player.uuid, host)
            }
        }

        player.sendSystemMessage(
            Component.literal(
                "Calibration $targetIndex: $id ${entry?.width}x${entry?.height} " +
                    "mode=$mode tops=$topA,$topB"
            )
        )
    }

    fun closeFor(uuid: UUID) {
        val host = open.remove(uuid) ?: return
        InteractionRouter.unregisterSurface(uuid, host)
        host.close()
        if (open.isEmpty()) PackSync.forget("calibration")
    }
}
