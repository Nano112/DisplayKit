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
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.NineSliceLayout
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.SurfaceFocus
import io.schemat.displaykit.surface.SurfaceHost
import io.schemat.displaykit.surface.SurfacePlacement
import io.schemat.displaykit.surface.layout.CrossAxis
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.PxPadding
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.ScrollNode
import io.schemat.displaykit.surface.layout.WidgetNode
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

/**
 * A sprite picker built entirely from the surface layout tree.
 *
 * This is the acceptance test for the whole surface-interaction plan: if a
 * framed, scrollable, closable window with hover and drag cannot be assembled
 * comfortably from [io.schemat.displaykit.surface.layout] on top of the
 * bubbling event model, the tree is wrong.
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
    private const val SCROLL_W = 6

    // Fixed geometry for the chrome around the grid. Named rather than
    // inlined so the COLS estimate below (which has to know how much width
    // the grid actually gets) can never drift from what the tree itself
    // builds.
    private const val PADDING = 10
    private const val BODY_GAP = 10
    private const val TAB_W = 130
    private const val TAB_H = 24
    private const val TITLE_H = 16

    // Snapping the frame to an exact nine-slice tiling grew it to 346x264,
    // and a fixed column count leaves the grid too narrow or lets it
    // overflow into the scrollbar. Derive the column count from the frame's
    // own width instead, so the grid always fills whatever space the tree
    // actually gives it: total width, minus the window's own padding, the
    // tab strip, the two inter-column gaps and the scrollbar, is what the
    // grid pane gets; STEP-pitch columns are chunked out of that.
    private val COLS: Int
        get() {
            val paneW = W - 2 * PADDING - TAB_W - 2 * BODY_GAP - SCROLL_W
            return maxOf(1, (paneW - SLOT) / STEP + 1)
        }

    private val FRAME = SpriteId("gui", "tooltip/background")
    // items first: gui sprites are mostly nine-slice panels sized for a
    // real screen, so they overflow a slot grid. Revisit when the picker can
    // scale a preview down to its cell.
    private val ATLASES = listOf("items", "blocks", "gui")

    /** A soft highlight applied to a grid icon while the pointer hovers it. */
    private val HOVER_TINT = DkColor(255, 255, 240, 160)

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
        var atlas: String = ATLASES.first()
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
     * Every handler that changes CONTENT (a new atlas tab, the initial open)
     * goes through here, and none of them may call `repaint` + `host.repaint()`
     * directly: a repaint draws a different page of sprites, allocating fresh
     * codepoints, and a codepoint the client's pack does not define renders as
     * a missing-glyph box. Before this existed, `open()` was the only path
     * that rebuilt, so every tab click turned the window to tofu.
     *
     * Rebuilding unconditionally would be just as wrong the other way: it makes
     * EVERY connected client re-download the pack on EVERY click. So we watch
     * both allocators across the repaint and rebuild only on growth.
     *
     * A scroll-only change never calls this -- see [repaintTree].
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

    /**
     * Re-paint the EXISTING tree and push it, without rebuilding the tree.
     *
     * [Surface.layout] constructs a fresh [ScrollNode] every time it runs,
     * whose `scrollPx` starts at zero -- so re-running it on every scroll
     * notch would snap the grid back to the top on every notch. Scroll and
     * thumb-drag handlers call this instead, which repaints the tree exactly
     * as it stands (with whatever `scrollPx` the drag/notch just set) and
     * only pushes the result to the client. Only a genuine content change --
     * switching atlas tabs -- goes through [repaintAndSync] and rebuilds the
     * tree from scratch.
     */
    private fun repaintTree(session: Session) {
        session.host.surface.paintTree()
        session.host.repaint()
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

        // Not atan2 alone: the client's built-in rotateY(PI) makes a text
        // display's readable side local +Z, so the bare angle presents the
        // window's back. Surface.yawFacing carries the correction.
        val yawDegrees = Surface.yawFacing(look)

        // Position is set below, once the surface's own pixelScale gives us
        // its real world size; Vec3d.ZERO here is just a placeholder.
        val surface = Surface(W, H, Vec3d.ZERO, targetWidthBlocks = 3f)
        // Square the window to the player regardless of which way they face.
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
        // until open(), so this fills the canvas and rebuilds the pack.
        repaintAndSync(session)
        // Then WAIT for the client to actually apply that pack. Spawning
        // straight away renders every newly-allocated codepoint as a
        // missing-glyph box, whose advance is the font default rather than the
        // sprite's -- so the rows measure wrong, the block measures wrong, and
        // the layers scatter. That is why opening the picker a second time
        // always looked right: the pack had landed by then.
        FabricPackIntegration.whenPackApplied(player.uuid) {
            // The player may have closed it (or logged out) while the pack was
            // downloading; only spawn if this session is still the live one.
            if (open[player.uuid] !== session) return@whenPackApplied
            host.open()
            InteractionRouter.registerSurface(player.uuid, host)
        }

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

    /**
     * Scrollbar thumb height for a track of [trackH] px given [max] scroll.
     *
     * Shared by the thumb's render and its drag handler so the two can never
     * drift apart: the render positions the thumb over `(trackH - thumbH)`,
     * so a drag handler computing `fraction` over anything else (plain
     * `trackH`, or a differently-rounded thumbH) is not that position's
     * inverse, and the thumb visibly lags the cursor mid-drag.
     */
    private fun thumbHeightFor(trackH: Int, max: Int): Int =
        if (max == 0) trackH else maxOf(32, trackH * trackH / (trackH + max))

    /**
     * Rebuild the layout tree from scratch and paint it.
     *
     * Only called for a genuine content change (initial open, atlas switch):
     * [Surface.layout] constructs a brand new [ScrollNode], so re-running it
     * legitimately resets scroll to the top. Scroll-only changes must go
     * through [repaintTree] instead, never through here.
     */
    private fun repaint(session: Session) {
        val all = spritesFor(session)
        val player = session.player

        session.host.surface.layout { root ->
            // The window's own nine-slice background, sized to the full
            // canvas and added to the root BEFORE the content column so it
            // draws behind everything. BoxNode now stacks every child at the
            // same origin (rather than only laying out its first), so this
            // can be a real sibling of `column` and paint with the rect the
            // tree gives it instead of a hardcoded Rect(0, 0, W, H).
            val frame = WidgetNode("frame", PxSize(W, H)) { p, r ->
                frameEntry?.let { p.frame(it, r) }
            }
            root.addChild(frame)

            val column = FlexNode("window", FlexDirection.COLUMN, gap = 4)
            column.padding = PxPadding.all(PADDING)

            // Title bar: fixed height, full width.
            val title = WidgetNode("title", PxSize(0, TITLE_H)) { p, r ->
                p.titleBar(r, "Sprites — ${session.atlas} (${all.size})") { closeFor(player.uuid) }
            }
            title.flexGrow = 0
            column.addChild(title)

            val body = FlexNode("body", FlexDirection.ROW, gap = BODY_GAP)
            body.flexGrow = 1

            // Tab strip.
            val tabs = FlexNode("tabs", FlexDirection.COLUMN, gap = 2)
            tabs.width = TAB_W
            for (atlas in ATLASES) {
                val selected = atlas == session.atlas
                val tab = WidgetNode("tab-$atlas", PxSize(TAB_W, TAB_H)) { p, r ->
                    val hovered = SurfaceFocus.state(player.uuid).hoveredId == "tab-$atlas"
                    p.tab("tab-$atlas", r, atlas, hovered || selected) {}
                }
                tab.onEvent = { e ->
                    when (e) {
                        is SurfaceEvent.Click -> {
                            session.atlas = atlas
                            repaintAndSync(session)
                            EventResult.CONSUMED
                        }
                        // Not load-bearing -- SurfaceEvents.dispatch delivers
                        // these only to the target and ignores the return
                        // value -- but reporting them handled keeps the
                        // intent explicit and matches the click branch. The
                        // render lambda above reads SurfaceFocus.hoveredId;
                        // SurfaceHost.tick() re-runs paintTree() on every
                        // hover change before pushing, so no repaint call is
                        // needed here.
                        is SurfaceEvent.PointerEnter, is SurfaceEvent.PointerExit ->
                            EventResult.CONSUMED
                        else -> EventResult.PASS
                    }
                }
                tabs.addChild(tab)
            }
            body.addChild(tabs)

            // Scrolling grid.
            val pane = ScrollNode("grid")
            pane.stepPx = STEP
            pane.flexGrow = 1
            pane.onEvent = { e ->
                if (e is SurfaceEvent.Scroll && pane.scrollBy(e.delta)) {
                    repaintTree(session)
                    EventResult.CONSUMED
                } else EventResult.PASS
            }
            for (rowIndex in all.indices.step(COLS)) {
                // STEP tall, not SLOT: ScrollNode stacks children contiguously
                // with no gap, so the row's own height IS the vertical row
                // pitch. Sizing it to the 18px cell instead of the 20px step
                // drifts 2px per notch against stepPx and, because the canvas
                // has no clipping and visibleChildren() only emits WHOLE
                // children, eventually drops a row off the bottom edge.
                val row = FlexNode(
                    "row-$rowIndex", FlexDirection.ROW,
                    gap = STEP - SLOT, crossAxis = CrossAxis.CENTER
                )
                row.height = STEP
                for (i in rowIndex until minOf(rowIndex + COLS, all.size)) {
                    val entry = all[i]
                    val cell = WidgetNode("cell-$i", PxSize(SLOT, SLOT)) { p, r ->
                        val hovered = SurfaceFocus.state(player.uuid).hoveredId == "cell-$i"
                        p.slot(r.x, r.y)
                        // Fitted, not native: gui sprites are whole panels
                        // (some hundreds of pixels across) and at 1:1 they
                        // bury the grid.
                        p.iconFitted(
                            entry, r.x + 1, r.y + 1, SLOT - 2, SLOT - 2,
                            tint = if (hovered) HOVER_TINT else null
                        )
                    }
                    cell.onEvent = { e ->
                        when (e) {
                            is SurfaceEvent.Click -> {
                                player.sendSystemMessage(
                                    Component.literal("${entry.id}  ${entry.width}x${entry.height}")
                                )
                                EventResult.CONSUMED
                            }
                            // See the matching comment on the tab handler
                            // above -- SurfaceHost.tick() repaints the tree
                            // on hover change, so this node does not have to.
                            is SurfaceEvent.PointerEnter, is SurfaceEvent.PointerExit ->
                                EventResult.CONSUMED
                            else -> EventResult.PASS
                        }
                    }
                    row.addChild(cell)
                }
                pane.addChild(row)
            }
            body.addChild(pane)

            // Scrollbar with a grabbable thumb. A plain WidgetNode never
            // stretches to fill a flex container's cross axis in this tree
            // (only flexGrow'd children get a forced cross size, and that
            // only grows the MAIN axis of their own parent) -- wrapping the
            // bar in its own single-child COLUMN, and growing IT inside that
            // wrapper, fills the bar's height to the grid's without also
            // fighting the tab strip and grid pane for width in `body`.
            val barWrap = FlexNode("scrollbar-wrap", FlexDirection.COLUMN)
            val bar = WidgetNode("scrollbar", PxSize(SCROLL_W, 0)) { p, r ->
                p.scrollTrack(r)
                val max = pane.maxScroll()
                val thumbH = thumbHeightFor(r.h, max)
                val thumbY = if (max == 0) r.y else r.y + (pane.scrollPx * (r.h - thumbH)) / max
                p.scrollThumb(Rect(r.x, thumbY, SCROLL_W, thumbH))
            }
            bar.width = SCROLL_W
            bar.flexGrow = 1
            bar.onGrabMove = { _, y ->
                val r = bar.rect()
                val max = pane.maxScroll()
                // The inverse of the render's thumbY: dividing by the plain
                // track height (rather than the same `r.h - thumbH` span the
                // render positions the thumb over) makes the thumb visibly
                // lag the cursor mid-drag, worse the taller the thumb.
                val thumbH = thumbHeightFor(r.h, max)
                val span = (r.h - thumbH).coerceAtLeast(1)
                val fraction = ((y - r.y).toDouble() / span).coerceIn(0.0, 1.0)
                if (pane.scrollTo((fraction * max).toInt())) repaintTree(session)
            }
            barWrap.addChild(bar)
            body.addChild(barWrap)

            column.addChild(body)
            root.addChild(column)
        }
        session.host.surface.paintTree()
    }
}
