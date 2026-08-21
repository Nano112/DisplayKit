package io.schemat.displaykit.showcase

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.pack.SpacingFontProvider
import io.schemat.displaykit.pack.SpriteFontProvider
import io.schemat.displaykit.pack.SpriteSliceProvider
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.NineSliceLayout
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceHost
import io.schemat.displaykit.surface.SurfacePlacement
import io.schemat.displaykit.surface.button
import io.schemat.displaykit.surface.scrollThumb
import io.schemat.displaykit.surface.scrollTrack
import io.schemat.displaykit.surface.tab
import io.schemat.displaykit.surface.titleBar
import io.schemat.displaykit.ui.InteractionRouter
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2

/**
 * A sprite picker built entirely from surface parts.
 *
 * This is the acceptance test for the chrome layer: if a framed, scrollable,
 * closable window cannot be assembled comfortably from the parts, the parts are
 * wrong.
 */
object PickerWindow {

    // Design targets for the frame -- NOT the final pixel size. The frame
    // sprite (gui/tooltip/background, 100x100, 9px border, 82x82 centre
    // tile) can only grow in whole centre-tile steps without its final tile
    // overlapping its neighbour and z-fighting (both tiles are coplanar
    // glyphs on the same surface), so the actual size below is rounded up by
    // NineSliceLayout.exactSizeFor to whatever tiles exactly -- 346x264 for
    // these targets (9 + 4*82 + 9 wide, 9 + 3*82 + 9 tall).
    private const val MIN_W = 320
    private const val MIN_H = 220

    private const val STEP = 20
    private const val SLOT = 18
    private const val GRID_X = 150
    private const val GRID_Y = 30
    private const val MARGIN = 10
    private const val SCROLL_W = 6

    // Snapping the frame to an exact nine-slice tiling grew it to 346x264,
    // and the grid kept its old 8x6 shape -- leaving a third of the window
    // empty. Derive the counts from the frame instead, so the content always
    // fills whatever size the tiling settles on.
    private val COLS: Int
        get() = maxOf(1, (W - GRID_X - MARGIN - SCROLL_W - 2 - SLOT) / STEP + 1)
    private val ROWS: Int
        get() = maxOf(1, (H - GRID_Y - MARGIN - SLOT) / STEP + 1)

    /** Clear of the grid's last column by two pixels. */
    private val SCROLL_X: Int get() = GRID_X + (COLS - 1) * STEP + SLOT + 2
    private val TRACK_H: Int get() = (ROWS - 1) * STEP + SLOT

    private val FRAME = SpriteId("gui", "tooltip/background")
    private val ATLASES = listOf("gui", "items", "blocks")

    /** The frame sprite's manifest entry, resolved once. */
    private val frameEntry: SpriteEntry? by lazy { SpriteIndex.bundled.get(FRAME) }

    /**
     * The window's actual pixel size: the smallest size at least
     * [MIN_W]x[MIN_H] that tiles [FRAME] with no overlap. Falls back to the
     * design target if the frame sprite is somehow missing from the
     * manifest, so a broken lookup degrades to the old overlap behaviour
     * rather than crashing the picker.
     */
    private val frameSize: Pair<Int, Int> by lazy {
        frameEntry?.let { NineSliceLayout.exactSizeFor(it, MIN_W, MIN_H) } ?: (MIN_W to MIN_H)
    }
    private val W: Int get() = frameSize.first
    private val H: Int get() = frameSize.second

    /** Comfortable viewing distance scales with the window's own width. */
    private const val VIEW_DISTANCE_WIDTH_FACTOR = 1.6
    private const val MIN_VIEW_DISTANCE_BLOCKS = 3.0

    private class Session(val host: SurfaceHost, val player: ServerPlayer) {
        var atlas: String = "gui"
        var scroll: Int = 0
    }

    private val open = ConcurrentHashMap<UUID, Session>()

    @Volatile private var disconnectHookInstalled = false

    /**
     * Drop a disconnecting player's session.
     *
     * `InteractionRouter.cleanupPlayer` closes the surface hosts, but this map
     * is the picker's own and holds a hard `ServerPlayer` plus every closure
     * that captured it — a leak for the life of the server. The hook lives here
     * rather than in `core` so `core` gains no dependency on the showcase.
     * Installed lazily on first open, so `/dk picker` is the only thing that
     * ever pays for it.
     */
    private fun installDisconnectHook() {
        if (disconnectHookInstalled) return
        disconnectHookInstalled = true
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            closeFor(handler.player.uuid)
        }
    }

    /**
     * Repaint, push to the client, and resend the pack IF the repaint asked for
     * glyphs the client has never seen.
     *
     * Every handler goes through here, and none of them may call `repaint` +
     * `host.repaint()` directly: a repaint draws a different page of sprites
     * and a differently placed scrollbar, allocating fresh codepoints, and a
     * codepoint the client's pack does not define renders as a missing-glyph
     * box. Before this existed, `open()` was the only path that rebuilt, so
     * every tab and scroll click turned the window to tofu.
     *
     * Rebuilding unconditionally would be just as wrong the other way: it makes
     * EVERY connected client re-download the pack on EVERY click. So we watch
     * both allocators across the repaint and rebuild only on growth.
     */
    private fun repaintAndSync(session: Session) {
        val glyphsBefore = SpriteGlyphs.requested().size
        val slicesBefore = SpriteSliceProvider.variantCount()

        repaint(session)
        session.host.repaint()

        val grew = SpriteGlyphs.requested().size > glyphsBefore ||
            SpriteSliceProvider.variantCount() > slicesBefore
        if (grew) FabricPackIntegration.rebuildAndResendToAll()
    }

    fun open(player: ServerPlayer) {
        closeFor(player.uuid)
        installDisconnectHook()

        // The chrome needs slices, glyphs and spacing; register and push once.
        FabricPackIntegration.registerAssetProvider(SpriteFontProvider)
        FabricPackIntegration.registerAssetProvider(SpacingFontProvider)
        FabricPackIntegration.registerAssetProvider(SpriteSliceProvider)

        val ref = FabricPlayerRef(player)
        val eye = ref.eyePosition()
        val look = ref.lookDirection()

        val yawDegrees = Math.toDegrees(atan2(look.x, look.z)).toFloat()

        // Position is set below, once the surface's own pixelScale gives us
        // its real world size; Vec3d.ZERO here is just a placeholder.
        val surface = Surface(W, H, Vec3d.ZERO, targetWidthBlocks = 3f)
        // Square the window to the player regardless of which way they're
        // facing: an unrotated surface faces -Z (see SurfacePicking), which
        // matches a viewer whose look direction is +Z (atan2(0, 1) == 0), so
        // yawDegrees = atan2(look.x, look.z) turns the surface's front to
        // face wherever the player is looking.
        surface.yawDegrees = yawDegrees

        val worldWidth = (surface.widthPx * surface.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        val worldHeight = (surface.heightPx * surface.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()

        // A fixed 2.5-block spawn distance put a 3-block-wide window closer
        // to the player's face than the window itself was wide. Scale
        // distance with the window's own width instead, so it clears the
        // player regardless of how big the frame ends up.
        val distance = maxOf(worldWidth * VIEW_DISTANCE_WIDTH_FACTOR, MIN_VIEW_DISTANCE_BLOCKS)
        // Roughly eye height: aim for the window's CENTRE at eye level, not
        // its top edge.
        val center = Vec3d(eye.x + look.x * distance, eye.y, eye.z + look.z * distance)

        // Surface.position is the canvas TOP-LEFT corner, so pointing it
        // straight at `center` would hang the window down-and-right of where
        // the player is looking. SurfacePlacement shifts the origin back by
        // half the window's world size -- along the surface's own rotated
        // right vector for width, since yawDegrees above turns it to face
        // the player -- so the CENTRE lands on the look ray instead.
        surface.position = SurfacePlacement.centeredOrigin(center, yawDegrees, worldWidth, worldHeight)
        // Alpha 100-149 and 200-249 are DkColor shader sentinels (glass /
        // corner-radius) -- 190 sits outside both. Dark neutral graphite so
        // the vanilla chrome (frame, tabs, slots) stays legible against it.
        surface.backdrop = DkColor(190, 18, 19, 22)
        // A real stretched block display behind the sprite plane. Glyphs are
        // single-sided and unlit, so without it the chrome reads as floating
        // decals against the sky rather than one panel.
        surface.backingBlock = BlockStateRef.BLACK_CONCRETE
        val host = SurfaceHost(DisplayKit.platform, ref, surface)
        val session = Session(host, player)
        open[player.uuid] = session

        // Paint and sync the pack BEFORE spawning: host.repaint() is a no-op
        // until open(), so this fills the canvas and rebuilds the pack, and
        // host.open() then spawns the entity with content the client can read.
        repaintAndSync(session)
        host.open()
        InteractionRouter.registerSurface(player.uuid, host)

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

            titleBar(Rect(10, 10, W - 20, 16), "Sprites — ${session.atlas} (${all.size})") {
                closeFor(session.player.uuid)
            }

            ATLASES.forEachIndexed { i, atlas ->
                tab("tab-$atlas", Rect(10, GRID_Y + i * 26, 130, 24), atlas, atlas == session.atlas) {
                    session.atlas = atlas
                    session.scroll = 0
                    repaintAndSync(session)
                }
            }

            page.forEachIndexed { i, entry ->
                val cx = GRID_X + (i % COLS) * STEP
                val cy = GRID_Y + (i / COLS) * STEP
                slot(cx, cy)
                icon(entry, cx + 1, cy + 1)
                region("cell-$i", Rect(cx, cy, SLOT, SLOT)) {
                    session.player.sendSystemMessage(
                        Component.literal("${entry.id}  ${entry.width}x${entry.height}")
                    )
                }
            }

            // Track sits at x=310, clear of the grid's last column (which ends at
            // x=308) — moved right from the original x=304, which overlapped that
            // column by 4px. 310+6=316 stays inside the frame regardless of its
            // exact width. Track height (TRACK_H) matches the grid's own height
            // instead of an arbitrary taller figure.
            scrollTrack(Rect(SCROLL_X, GRID_Y, 6, TRACK_H))
            val thumbY = if (maxScroll == 0) GRID_Y
                         else GRID_Y + (session.scroll * (TRACK_H - 32)) / maxScroll
            scrollThumb(Rect(SCROLL_X, thumbY, 6, 32))

            // scroll by clicking the track above or below the thumb
            region("scroll-up", Rect(SCROLL_X, GRID_Y, 6, maxOf(1, thumbY - GRID_Y))) {
                session.scroll--; repaintAndSync(session)
            }
            val belowY = thumbY + 32
            if (belowY < GRID_Y + TRACK_H) {
                region("scroll-down", Rect(SCROLL_X, belowY, 6, GRID_Y + TRACK_H - belowY)) {
                    session.scroll++; repaintAndSync(session)
                }
            }
        }
    }
}
