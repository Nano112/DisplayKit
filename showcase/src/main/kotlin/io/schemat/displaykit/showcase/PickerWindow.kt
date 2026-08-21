package io.schemat.displaykit.showcase

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.pack.SpacingFontProvider
import io.schemat.displaykit.pack.SpriteFontProvider
import io.schemat.displaykit.pack.SpriteSliceProvider
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceHost
import io.schemat.displaykit.surface.button
import io.schemat.displaykit.surface.scrollThumb
import io.schemat.displaykit.surface.scrollTrack
import io.schemat.displaykit.surface.tab
import io.schemat.displaykit.surface.titleBar
import io.schemat.displaykit.ui.InteractionRouter
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A sprite picker built entirely from surface parts.
 *
 * This is the acceptance test for the chrome layer: if a framed, scrollable,
 * closable window cannot be assembled comfortably from the parts, the parts are
 * wrong.
 */
object PickerWindow {

    private const val W = 320
    private const val H = 220
    private const val COLS = 8
    private const val ROWS = 6
    private const val STEP = 20
    private const val GRID_X = 150
    private const val GRID_Y = 30
    // Grid's last column runs to x = GRID_X + 7*STEP + 18 = 308; the scroll
    // track sits clear of it at x=310 (see the reviewed layout note below).
    private const val SCROLL_X = 310

    private val FRAME = SpriteId("gui", "tooltip/background")
    private val ATLASES = listOf("gui", "items", "blocks")

    private class Session(val host: SurfaceHost, val player: ServerPlayer) {
        var atlas: String = "gui"
        var scroll: Int = 0
    }

    private val open = ConcurrentHashMap<UUID, Session>()

    fun open(player: ServerPlayer) {
        closeFor(player.uuid)

        // The chrome needs slices, glyphs and spacing; register and push once.
        FabricPackIntegration.registerAssetProvider(SpriteFontProvider)
        FabricPackIntegration.registerAssetProvider(SpacingFontProvider)
        FabricPackIntegration.registerAssetProvider(SpriteSliceProvider)

        val ref = FabricPlayerRef(player)
        val eye = ref.eyePosition()
        val look = ref.lookDirection()
        // place the window 2.5 blocks ahead, top-left offset so it reads centred
        val pos = Vec3d(eye.x + look.x * 2.5, eye.y + 0.6, eye.z + look.z * 2.5)

        val surface = Surface(W, H, pos, targetWidthBlocks = 3f)
        val host = SurfaceHost(DisplayKit.platform, ref, surface)
        val session = Session(host, player)
        open[player.uuid] = session

        repaint(session)
        host.open()
        InteractionRouter.registerSurface(player.uuid, host)

        FabricPackIntegration.rebuildAndResendToAll()
        player.sendSystemMessage(
            Component.literal("Picker open. Click a slot to copy its id; the cross closes it.")
        )
    }

    fun closeFor(uuid: UUID) {
        val s = open.remove(uuid) ?: return
        InteractionRouter.unregisterSurface(uuid, s.host)
        s.host.close()
    }

    private fun spritesFor(session: Session): List<SpriteEntry> =
        SpriteIndex.bundled.all()
            .filter { it.id.atlas == session.atlas && it.glyphEligible }
            .sortedBy { it.id.sprite }

    private fun repaint(session: Session) {
        val all = spritesFor(session)
        val perPage = COLS * ROWS
        val maxScroll = maxOf(0, (all.size - 1) / COLS - ROWS + 1)
        session.scroll = session.scroll.coerceIn(0, maxScroll)
        val start = session.scroll * COLS
        val page = all.drop(start).take(perPage)

        val frameSprite = SpriteIndex.bundled.get(FRAME)

        session.host.surface.paint {
            frameSprite?.let { frame(it, Rect(0, 0, W, H)) }

            titleBar(Rect(10, 10, 300, 16), "Sprites — ${session.atlas} (${all.size})") {
                closeFor(session.player.uuid)
            }

            ATLASES.forEachIndexed { i, atlas ->
                tab("tab-$atlas", Rect(10, GRID_Y + i * 26, 130, 24), atlas, atlas == session.atlas) {
                    session.atlas = atlas
                    session.scroll = 0
                    repaint(session)
                    session.host.repaint()
                }
            }

            page.forEachIndexed { i, entry ->
                val cx = GRID_X + (i % COLS) * STEP
                val cy = GRID_Y + (i / COLS) * STEP
                slot(cx, cy)
                icon(entry, cx + 1, cy + 1)
                region("cell-$i", Rect(cx, cy, 18, 18)) {
                    session.player.sendSystemMessage(
                        Component.literal("${entry.id}  ${entry.width}x${entry.height}")
                    )
                }
            }

            // Track sits at x=310, clear of the grid's last column (which ends at
            // x=308) — moved right from the original x=304, which overlapped that
            // column by 4px. 310+6=316 stays inside the 320-wide frame.
            scrollTrack(Rect(SCROLL_X, GRID_Y, 6, 180))
            val thumbY = if (maxScroll == 0) GRID_Y
                         else GRID_Y + (session.scroll * (180 - 32)) / maxScroll
            scrollThumb(Rect(SCROLL_X, thumbY, 6, 32))

            // scroll by clicking the track above or below the thumb
            region("scroll-up", Rect(SCROLL_X, GRID_Y, 6, maxOf(1, thumbY - GRID_Y))) {
                session.scroll--; repaint(session); session.host.repaint()
            }
            val belowY = thumbY + 32
            if (belowY < GRID_Y + 180) {
                region("scroll-down", Rect(SCROLL_X, belowY, 6, GRID_Y + 180 - belowY)) {
                    session.scroll++; repaint(session); session.host.repaint()
                }
            }
        }
    }
}
