package io.schemat.displaykit.showcase

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.pack.SpriteSliceProvider
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.GlyphPlacement
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.NineSliceLayout
import io.schemat.displaykit.surface.NineSlicePainter
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.RenderMode
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
import io.schemat.displaykit.surface.scrollThumbHeight
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

    /**
     * The scrollbar thumb sprite, for [prewarmScrollThumb]. Must match the
     * private `SCROLL_THUMB` id [io.schemat.displaykit.surface.scrollThumb]
     * itself draws with, in `SurfaceParts.kt` -- that id is not exported, so
     * this is a second literal of the same sprite id, not a shared constant.
     */
    private val SCROLL_THUMB_SPRITE = SpriteId("gui", "widget/scroller")

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

    private class Session(
        val host: SurfaceHost,
        val player: ServerPlayer,
        /**
         * True for `/dk picker nopack`. Skips every step that only exists to
         * feed the generated resource pack (asset-provider registration,
         * glyph/slice pre-warming, the `whenPackApplied` open gate) -- none of
         * which [RenderMode.ENTITIES] ever allocates into in the first place,
         * so running them anyway would be dead work at best and, for the pack
         * registration, would make THIS session's `/dk picker nopack` still
         * require every OTHER connected player to download a pack it never
         * uses.
         */
        val entitiesMode: Boolean = false
    ) {
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
     * Resend the pack if [glyphsBefore]/[slicesBefore] no longer match what is
     * on record -- i.e. something painted since they were snapshotted
     * allocated a new variant.
     *
     * A sprite's vertical placement is baked into its font ascent, so the same
     * sprite at a new Y is a new codepoint. Anything the client's pack does not
     * have renders as tofu -- which is what scrolling and dragging the thumb
     * used to produce.
     *
     * Rebuilding unconditionally would be just as wrong the other way: it makes
     * EVERY connected client re-download the pack on EVERY change. So this only
     * rebuilds on growth. Kept as its own function for [repaintTree], which
     * (being scroll-only) never registers asset providers and so cannot use
     * [withPackSync]; [repaintAndSync] below gets the identical check for free
     * from that shared helper.
     */
    private fun syncPackIfGlyphsGrew(glyphsBefore: Int, slicesBefore: Int) {
        val grew = SpriteGlyphs.requested().size > glyphsBefore ||
            SpriteSliceProvider.variantCount() > slicesBefore
        if (grew) FabricPackIntegration.rebuildAndResendToAll()
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
     * [repaint] itself pre-warms the grid and the scrollbar thumb's variants
     * before painting the current page, and [prewarmCursor] does the same for
     * the on-surface pointer -- so [withPackSync]'s growth check almost always
     * finds nothing new, and this is the ONE resend that ships them all.
     *
     * `/dk picker nopack` (`session.entitiesMode`) bypasses [withPackSync]
     * entirely rather than merely skipping its own growth: RenderMode.ENTITIES
     * never allocates a glyph or slice codepoint, so there is nothing to
     * register or resend, and registering the providers anyway would make
     * THIS session's nopack window force every OTHER connected player to
     * download a pack it never uses -- see [Session.entitiesMode].
     */
    private fun repaintAndSync(session: Session) {
        if (session.entitiesMode) {
            repaint(session)
            session.host.repaint()
            return
        }
        withPackSync {
            repaint(session)
            prewarmCursor()
            session.host.repaint()
        }
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
     *
     * The grid's pre-warm (see [repaint]) means a scroll notch normally needs
     * no new glyph at all -- every row shares the grid's one ascent phase, see
     * [prewarmGrid] -- but the scrollbar thumb can still drag to a Y this
     * session's pre-warm did not cover if the geometry it was computed from
     * (track height, max scroll) ever changes underneath it. This growth
     * check is what makes that safe rather than merely usually-fine.
     *
     * Deliberately does NOT go through [withPackSync]: that helper always
     * registers the asset providers, and a scroll/drag notch on a `nopack`
     * session must not be what first registers them (see [repaintAndSync]'s
     * KDoc) -- so this keeps its own narrow [syncPackIfGlyphsGrew] check,
     * which is a no-op read for a nopack session since nothing it does can
     * ever grow either table.
     */
    private fun repaintTree(session: Session) {
        val glyphsBefore = SpriteGlyphs.requested().size
        val slicesBefore = SpriteSliceProvider.variantCount()

        session.host.surface.paintTree()
        session.host.repaint()

        syncPackIfGlyphsGrew(glyphsBefore, slicesBefore)
    }

    /**
     * @param renderMode [RenderMode.AUTO] (the default) behaves exactly as
     *   before -- COMPOSITED once the pack has landed. `/dk picker nopack`
     *   passes [RenderMode.ENTITIES] explicitly, which skips the pack
     *   entirely: no asset-provider registration, no glyph/slice pre-warm, no
     *   `whenPackApplied` wait -- the window opens on the very next tick,
     *   rendered from vanilla's own atlas-sprite entities. Passing
     *   [RenderMode.COMPOSITED] explicitly is legal but pointless here: the
     *   picker never varies pack availability per-player, so it behaves
     *   identically to [RenderMode.AUTO] once the pack it already registers
     *   has landed.
     */
    fun open(player: ServerPlayer, renderMode: RenderMode = RenderMode.AUTO) {
        closeFor(player.uuid)
        installDisconnectHook()

        val entitiesMode = renderMode == RenderMode.ENTITIES
        // The chrome needs slices, glyphs and spacing; registration itself now
        // happens inside repaintAndSync's withPackSync call below, once per
        // content change rather than only here -- see that function's KDoc
        // for why entitiesMode still bypasses it entirely.

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
        surface.renderMode = renderMode
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
        val session = Session(host, player, entitiesMode = entitiesMode)
        open[player.uuid] = session

        if (entitiesMode) {
            // No pack, no wait: RenderMode.ENTITIES paints straight into
            // vanilla atlas-sprite entities, which every client already has,
            // so there is nothing to download and nothing to gate opening on.
            repaint(session)
            host.open()
            InteractionRouter.registerSurface(player.uuid, host)
        } else {
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
        }

        val modeNote = if (entitiesMode) " (nopack: no resource pack, one entity per sprite)" else ""
        player.sendSystemMessage(
            Component.literal("Picker open$modeNote. Click a slot to copy its id; the cross closes it.")
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
     * Rebuild the layout tree from scratch and paint it.
     *
     * Only called for a genuine content change (initial open, atlas switch):
     * [Surface.layout] constructs a brand new [ScrollNode], so re-running it
     * legitimately resets scroll to the top. Scroll-only changes must go
     * through [repaintTree] instead, never through here.
     *
     * Pre-warms the grid's and the scrollbar thumb's glyph variants (see
     * [prewarmGrid], [prewarmScrollThumb]) once the tree is placed but before
     * [Surface.paintTree] draws only the current page, so scrolling and
     * thumb-dragging afterwards need no new variant at all.
     */
    private fun repaint(session: Session) {
        val all = spritesFor(session)
        val player = session.player

        // Captured from inside the tree builder below so the pre-warm calls
        // after `layout {}` returns can read their REAL placed rects, rather
        // than re-deriving the chrome-above-the-grid arithmetic (padding,
        // title height, tab strip gap...) by hand -- which would drift the
        // moment any of that changes, exactly the kind of duplication
        // iconFitted's own sizing math had to avoid.
        lateinit var pane: ScrollNode
        lateinit var bar: WidgetNode
        var firstCell: WidgetNode? = null

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

            // STRETCH so a non-growing child (the title bar below) fills the
            // column's cross axis (width) instead of collapsing to its own
            // intrinsic width -- see FlexNode.measureSelf pass 1.
            val column = FlexNode("window", FlexDirection.COLUMN, crossAxis = CrossAxis.STRETCH, gap = 4)
            column.padding = PxPadding.all(PADDING)

            // Title bar: fixed height, full width. Width is supplied by the
            // column's STRETCH above; MIN_W - 2*PADDING here is just an
            // honest non-zero fallback (the design-target content width) so
            // this node is never zero-sized even if STRETCH stopped applying.
            val title = WidgetNode("title", PxSize(MIN_W - 2 * PADDING, TITLE_H)) { p, r ->
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
            pane = ScrollNode("grid")
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
                    if (firstCell == null) firstCell = cell
                    row.addChild(cell)
                }
                pane.addChild(row)
            }
            body.addChild(pane)

            // Scrollbar with a grabbable thumb. CrossAxis.STRETCH now works
            // for non-growing children too (see FlexNode.measureSelf pass
            // 1), so in principle `bar` could sit directly in `body` with
            // body.crossAxis = STRETCH and no flexGrow. Kept as a wrapper
            // instead: `body` is a ROW shared with `tabs` and `pane`, and
            // making body.crossAxis STRETCH would stretch THEM to the row's
            // full cross extent (height) too -- a change to their layout
            // this bugfix has no reason to make. Wrapping `bar` in its own
            // single-child COLUMN keeps the stretch (via flexGrow, growing
            // along the wrapper's own main axis) scoped to just the
            // scrollbar, fixed width via `bar.width` as before.
            val barWrap = FlexNode("scrollbar-wrap", FlexDirection.COLUMN)
            bar = WidgetNode("scrollbar", PxSize(SCROLL_W, 0)) { p, r ->
                p.scrollTrack(r)
                val max = pane.maxScroll()
                val thumbH = scrollThumbHeight(r.h, max)
                val rawY = if (max == 0) 0 else (pane.scrollPx * (r.h - thumbH)) / max
                // The line-pitch snap that used to live here now lives in
                // io.schemat.displaykit.surface.scrollThumb itself, so every
                // caller gets it rather than just this one -- see that
                // function's KDoc for why an unsnapped thumb forces a pack
                // reload. Nothing below needs to account for it: it is
                // idempotent over the already-aligned Y this window produces
                // (see prewarmScrollThumb's KDoc on why bar.rect().y sits on
                // a stable FONT_LINE_HEIGHT_PX phase here).
                val thumbY = r.y + rawY.coerceIn(0, (r.h - thumbH).coerceAtLeast(0))
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
                val thumbH = scrollThumbHeight(r.h, max)
                val span = (r.h - thumbH).coerceAtLeast(1)
                val fraction = ((y - r.y).toDouble() / span).coerceIn(0.0, 1.0)
                if (pane.scrollTo((fraction * max).toInt())) repaintTree(session)
            }
            barWrap.addChild(bar)
            body.addChild(barWrap)

            column.addChild(body)
            root.addChild(column)
        }
        // Both allocate SpriteGlyphs/SliceGlyphSource codepoints for the
        // pack build -- meaningless (and wasted) work under RenderMode.ENTITIES,
        // which never emits a glyph codepoint at all; see Session.entitiesMode.
        if (!session.entitiesMode) {
            prewarmGrid(all, firstCell)
            prewarmScrollThumb(bar, pane)
        }
        session.host.surface.paintTree()
    }

    /**
     * Allocate the ONE glyph variant every grid cell in [all] will need,
     * before [Surface.paintTree] paints only the current page.
     *
     * A sprite's rendered ascent depends only on its canvas Y modulo
     * [TextMetrics.FONT_LINE_HEIGHT_PX] (10) -- see
     * [io.schemat.displaykit.sprite.GlyphPlacement] -- and every grid row
     * sits [STEP] (20, itself a multiple of 10) apart, so every row shares
     * exactly the same phase as the first. [reference] is that first cell's
     * REAL placed rect, read after layout rather than re-derived from the
     * chrome above the grid (padding, title height, tab gap...), which would
     * drift the moment any of that changes -- the same reasoning that keeps
     * this fitted-size math ([SpriteEntry.fitHeight]/[SpriteEntry.scaledWidth])
     * itself shared with [io.schemat.displaykit.surface.Surface.iconFitted]
     * rather than hand-duplicated here.
     *
     * Costs one variant per sprite regardless of how many rows scroll past --
     * cheap even for the "items" atlas, which is the whole point: it turns
     * scrolling from something that grows the glyph table into something that
     * never does.
     */
    private fun prewarmGrid(all: List<SpriteEntry>, reference: WidgetNode?) {
        val y = reference?.rect()?.let { it.y + 1 } ?: return
        for (entry in all) {
            val h = entry.fitHeight(SLOT - 2, SLOT - 2)
            val ascent = GlyphPlacement.resolve(y, h)?.ascent ?: continue
            SpriteGlyphs.request(entry, ascent, h)
        }
    }

    /**
     * Allocate the pointer-cursor glyph before the first tick that would
     * otherwise allocate it lazily.
     *
     * [Surface.pointerEntityAt] composites the cursor sprite onto its OWN
     * tiny scratch canvas at a FIXED local y=0 -- the entity's on-screen
     * position moves by teleporting it (`entityOrigin`), never by a different
     * font ascent (see that function's KDoc). So, unlike the grid or the
     * thumb, there is exactly ONE variant here, not one per possible screen
     * Y. Left un-pre-warmed it is still allocated the first time
     * `SurfaceHost.tick()` calls `showPointer` -- entirely outside
     * [repaintAndSync]'s growth check -- so it can render as tofu until some
     * unrelated repaint happens to trigger a resend afterwards.
     */
    private fun prewarmCursor() {
        val id = Surface.pointerSpriteOverride ?: Surface.POINTER_SPRITE
        val entry = SpriteIndex.bundled.get(id) ?: return
        val ascent = GlyphPlacement.resolve(0, entry.height)?.ascent ?: return
        SpriteGlyphs.request(entry, ascent, entry.height)
    }

    /**
     * Allocate the scrollbar thumb's slice codepoints at the one ascent the
     * (now snapped -- see the `bar` render lambda above) thumb will ever
     * render at.
     *
     * This used to probe every `y mod FONT_LINE_HEIGHT_PX` phase with a
     * SYNTHETIC `Rect(0, phase, ...)` for `phase` in `0 until 10` -- cheap,
     * since [NineSlicePainter.prewarm] allocates without touching a canvas,
     * but WRONG in a way a full scroll-and-drag test caught. A first attempt
     * at fixing it -- probing `bar.rect().y` directly, the snap's real
     * minimum `thumbY` -- was ALSO wrong, in the opposite direction, for the
     * same underlying reason: `y mod 10` alone does not determine a crop's
     * resolved ascent.
     *
     * [GlyphPlacement.resolve] starts from the natural ascent for `y`'s own
     * row and, if that exceeds the crop's height, walks rows backward,
     * subtracting [TextMetrics.FONT_LINE_HEIGHT_PX] from the ascent each
     * step, until it fits or row 0 is exhausted. A short crop (this sprite's
     * 1px borders) needs at most one such step, EVER -- the natural ascent
     * is at most [TextMetrics.GLYPH_TOP_BEARING_PX] (7), and one step
     * subtracts a full 10, so one fallback row always suffices. But that one
     * step needs a row to fall back TO: at a `y` within the first
     * [TextMetrics.FONT_LINE_HEIGHT_PX] px of the canvas (row 0 is `y`'s own
     * natural row), there is no row -1, so resolution can fail there and
     * ONLY there -- succeeding, at a stable ascent, for every larger `y` on
     * the identical phase. Probing at `bar.rect().y` when the track happens
     * to start inside that first line (true of this synthetic-tree test,
     * false of the real window's ~30px chrome, but not something this
     * function should assume) reproduces exactly that gap: it warns and
     * skips a crop the render never draws at `y=0` either, then misses
     * pre-warming the different, stable ascent every larger same-phase `y`
     * -- i.e. every scroll notch after the first -- actually needs,
     * allocating it live instead and forcing a resend.
     *
     * Probing one line-pitch ABOVE the snap's minimum Y sidesteps this for
     * good: it is always at least [TextMetrics.FONT_LINE_HEIGHT_PX] px from
     * the canvas floor, so the one fallback step this sprite's crops could
     * ever need always has a row to land on, and per the paragraph above
     * that resolves to the SAME ascent every other `y` on this phase does
     * (`bar.rect().y` itself included, whether or not IT happened to have
     * the headroom). This is provably enough for a 1px-tall crop; it is
     * enough for anything nine-sliced DisplayKit currently ships, but a
     * crop taller than [TextMetrics.FONT_LINE_HEIGHT_PX] + 3px (needing TWO
     * fallback steps) would need two line-pitches of margin instead of one
     * -- [repaintTree]'s growth check remains the backstop if that ever
     * changes, or if thumb geometry (track height, [ScrollNode.maxScroll])
     * changes between this call and a scroll/drag.
     */
    private fun prewarmScrollThumb(bar: WidgetNode, pane: ScrollNode) {
        val sprite = SpriteIndex.bundled.get(SCROLL_THUMB_SPRITE) ?: return
        val r = bar.rect()
        if (r.h <= 0) return
        val thumbH = scrollThumbHeight(r.h, pane.maxScroll())
        val probeY = r.y + TextMetrics.FONT_LINE_HEIGHT_PX
        NineSlicePainter.prewarm(sprite, Rect(0, probeY, SCROLL_W, thumbH))
    }
}
