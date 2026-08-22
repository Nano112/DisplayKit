package io.schemat.displaykit.showcase

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.fabric.input.TerminalChatCapture
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
import io.schemat.displaykit.surface.NineSliceLayout
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceHost
import io.schemat.displaykit.surface.SurfacePlacement
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode
import io.schemat.displaykit.surface.terminal.TerminalCommands
import io.schemat.displaykit.surface.terminal.TerminalModel
import io.schemat.displaykit.surface.terminal.TerminalWidget
import io.schemat.displaykit.ui.InteractionRouter
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-world terminal that captures chat while the player looks at it.
 *
 * Built the same way `PickerWindow` is: a [TerminalWidget] tree assembled
 * inside [Surface.layout], repainted through [SurfaceHost]. The interesting
 * part is chat routing (see [TerminalChatCapture]) and the two distinct
 * repaint paths — [repaintAndSync] for a genuine content change (an appended
 * line, `clear`) and [repaintTree] for a scroll-only change — kept as
 * separate as `PickerWindow` keeps its own equivalents, for the identical
 * reason: [Surface.layout] rebuilds [TerminalWidget]'s tree from scratch, so
 * running it on every scroll notch would fight [TerminalWidget]'s own
 * stick-to-bottom bookkeeping.
 */
object TerminalWindow {

    // Design targets for the frame -- see PickerWindow's identical constants
    // for why the actual size below (exactSizeFor) differs from these.
    private const val MIN_W = 300
    private const val MIN_H = 200

    private const val PADDING = 10
    private const val TITLE_H = 16
    private const val ROW_H = TextMetrics.FONT_LINE_HEIGHT_PX
    private const val SCROLL_W = 6

    private val FRAME = SpriteId("gui", "tooltip/background")

    private val frameEntry: SpriteEntry? by lazy { SpriteIndex.bundled.get(FRAME) }

    private val frameSize: Pair<Int, Int> by lazy {
        frameEntry?.let { NineSliceLayout.exactSizeFor(it, MIN_W, MIN_H) } ?: (MIN_W to MIN_H)
    }
    private val W: Int get() = frameSize.first
    private val H: Int get() = frameSize.second

    /** Row/title/prompt content width: full frame width, minus padding on both sides. */
    private val CONTENT_W: Int get() = W - 2 * PADDING

    /**
     * How many characters fit [CONTENT_W] at the widest common glyph — used
     * only to size [TerminalModel]'s wrap column, not for real layout (the
     * tree itself always fills whatever width it is actually given; see
     * [TerminalWidget]'s KDoc on `contentWidth`). Deliberately generous
     * (assumes every character is the 6px ASCII average) so text wraps a
     * little early rather than a little late.
     */
    private val COLUMNS: Int get() = maxOf(8, CONTENT_W / 6)

    private const val VIEW_DISTANCE_WIDTH_FACTOR = 1.6
    private const val MIN_VIEW_DISTANCE_BLOCKS = 3.0

    private class Session(
        val host: SurfaceHost,
        val player: ServerPlayer,
        val model: TerminalModel,
        val widget: TerminalWidget
    )

    private val open = ConcurrentHashMap<UUID, Session>()

    @Volatile private var hooksInstalled = false

    private fun installHooksOnce() {
        if (hooksInstalled) return
        hooksInstalled = true
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            closeFor(handler.player.uuid)
        }
        // See TerminalChatCapture's KDoc for why this indirection exists
        // instead of fabric depending on the showcase module directly.
        TerminalChatCapture.router = { uuid, message -> onChatLine(uuid, message) }
    }

    fun open(player: ServerPlayer) {
        closeFor(player.uuid)
        installHooksOnce()

        // Asset-provider registration now happens inside repaintAndSync's
        // withPackSync call below, once per content change rather than only
        // here -- see PickerWindow.open() for the identical move.

        val ref = FabricPlayerRef(player)
        val eye = ref.eyePosition()
        val look = ref.lookDirection()
        val yawDegrees = Surface.yawFacing(look)

        val surface = Surface(W, H, Vec3d.ZERO, targetWidthBlocks = 3f)
        surface.renderMode = RenderMode.AUTO
        surface.yawDegrees = yawDegrees

        val worldWidth = (surface.widthPx * surface.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        val worldHeight = (surface.heightPx * surface.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        val distance = maxOf(worldWidth * VIEW_DISTANCE_WIDTH_FACTOR, MIN_VIEW_DISTANCE_BLOCKS)
        val center = Vec3d(eye.x + look.x * distance, eye.y, eye.z + look.z * distance)
        surface.position = SurfacePlacement.centeredOrigin(center, yawDegrees, worldWidth, worldHeight)
        surface.backdrop = DkColor(190, 18, 19, 22)
        surface.backingBlock = BlockStateRef.BLACK_CONCRETE

        val host = SurfaceHost(DisplayKit.platform, ref, surface)
        val model = TerminalModel(columns = COLUMNS)
        val widget = TerminalWidget(
            model = model,
            contentWidth = CONTENT_W,
            rowHeightPx = ROW_H,
            scrollBarWidth = SCROLL_W,
            titleBarHeight = TITLE_H,
            padding = PADDING
        )
        val session = Session(host, player, model, widget)
        open[player.uuid] = session

        repaintAndSync(session)

        FabricPackIntegration.whenPackApplied(player.uuid) {
            if (open[player.uuid] !== session) return@whenPackApplied
            host.open()
            InteractionRouter.registerSurface(player.uuid, host)
        }

        player.sendSystemMessage(
            Component.literal(
                "Terminal open. Look at it and type in chat; scroll to review history. " +
                    "The cross closes it."
            )
        )
    }

    fun closeFor(uuid: UUID) {
        val s = open.remove(uuid) ?: return
        InteractionRouter.unregisterSurface(uuid, s.host)
        s.host.close()
    }

    /**
     * Run on the SERVER thread (see [TerminalChatCapture]'s KDoc) with one
     * captured chat line for [uuid]'s terminal.
     */
    private fun onChatLine(uuid: UUID, message: String) {
        val session = open[uuid] ?: return
        TerminalCommands.execute(session.model, message)
        repaintAndSync(session)
    }

    /**
     * Rebuild [TerminalWidget]'s tree from scratch, push it, and resend the
     * pack if that repaint allocated new glyph variants — the ONLY path that
     * may run after [TerminalModel] itself changed (an appended line, a
     * `clear`), for the same reason `PickerWindow.repaintAndSync` exists:
     * [TerminalWidget.build] constructs a brand-new pane every time, so
     * calling it on a purely-scroll change would undo the very scroll that
     * triggered it. [TerminalWidget.afterLayout] runs right after, while the
     * placement this build just produced is still current, so its
     * stick-to-bottom check sees real geometry.
     *
     * The [PackSync.withPackSync] wrapper is the fix for exactly the bug that made the
     * terminal ship illegible: without it, the server-start pack (built
     * before any provider had registered a single glyph or slice) is all the
     * client ever has, so every spacing advance and chrome slice this window
     * draws renders as a missing-glyph box. See [PackSync.withPackSync]'s KDoc.
     */
    private fun repaintAndSync(session: Session) {
        PackSync.withPackSync("terminal") {
            val frame = frameEntry
            session.host.surface.layout { root ->
                if (frame != null) {
                    root.addChild(WidgetNode("frame", PxSize(W, H)) { p, r -> p.frame(frame, r) })
                }
                session.widget.build(
                    root = root,
                    title = "Terminal",
                    promptHint = "type a command...",
                    onClose = { closeFor(session.player.uuid) },
                    onScrollChanged = { repaintTree(session) }
                )
            }
            session.widget.afterLayout()
            session.host.surface.paintTree()
            session.host.repaint()
        }
    }

    /**
     * Repaint the EXISTING tree after a scroll notch or thumb drag already
     * moved the pane in place — never rebuilds, so it cannot fight
     * [TerminalWidget]'s stick-to-bottom bookkeeping the way calling
     * [repaintAndSync] here would.
     */
    private fun repaintTree(session: Session) {
        session.host.surface.paintTree()
        session.host.repaint()
    }
}
