package io.schemat.displaykit.surface.terminal

import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.SurfaceNodeMarker
import io.schemat.displaykit.surface.layout.CrossAxis
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.PxPadding
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.ScrollNode
import io.schemat.displaykit.surface.layout.SurfaceNode
import io.schemat.displaykit.surface.layout.WidgetNode
import io.schemat.displaykit.surface.scrollThumb
import io.schemat.displaykit.surface.scrollTrack
import io.schemat.displaykit.surface.titleBar

/**
 * A [ScrollNode] that also arms chat capture (see
 * [io.schemat.displaykit.surface.SurfaceFocus.isTextArmed]) while the
 * pointer sits inside it.
 *
 * A dedicated subclass rather than marking [ScrollNode] itself: every OTHER
 * scroll pane in the codebase (the sprite picker's grid, for one) must NOT
 * start arming chat capture just because it happens to be scrollable — the
 * two markers are independent capabilities that merely share one ancestor
 * walk (`SurfaceFocus`'s private `hasMarkedAncestor`). [ScrollNode] was
 * opened specifically to allow this.
 */
class TerminalScrollNode(id: String) : ScrollNode(id), SurfaceNodeMarker.TextCapturing

/**
 * An in-world terminal, built entirely from the
 * [io.schemat.displaykit.surface.layout] tree: a title bar, a scrolling
 * column of [TerminalModel] rows, a scrollbar, and a fixed prompt row.
 *
 * This class owns tree assembly and scroll bookkeeping only; it knows
 * nothing about Minecraft, packets, or chat routing. A fabric-side window
 * (this feature's equivalent of `PickerWindow`) owns one [TerminalWidget]
 * per open terminal, feeds captured chat lines into [model], and calls
 * [build] + [afterLayout] on every content change — the same two-step shape
 * `PickerWindow.repaint` uses (build the tree, THEN read back the geometry
 * placement produced), because [io.schemat.displaykit.surface.Surface.layout]
 * measures and places the tree only AFTER the builder lambda returns, so
 * nothing in [build] itself can rely on real rects yet.
 *
 * **Stick to bottom unless scrolled away.** [scrolledAway] starts `false`: a
 * freshly opened terminal follows its newest line. [build] always
 * constructs a BRAND NEW [pane] — [io.schemat.displaykit.surface.Surface.layout]
 * rebuilds its whole tree from scratch on every call (see
 * `PickerWindow.repaintTree`'s KDoc for why), so a new pane always starts at
 * `scrollPx = 0` regardless of where the old one was. [build] therefore
 * snapshots the outgoing pane's `scrollPx` into [lastScrollPx] before
 * replacing it, and [afterLayout] restores the new pane to EITHER the
 * bottom (if [scrolledAway] is false — the common case, so new lines are
 * visible the instant they land) OR that snapshot (if true, so a user
 * mid-scroll sees their place held rather than the view snapping to the top
 * OR jumping back to the bottom out from under them). The scroll notch and
 * thumb-drag handlers [build] wires call [noteUserScroll], which is what
 * actually flips [scrolledAway]: true the moment a user action leaves the
 * pane short of its max scroll, false again the moment one brings it back
 * to the max — so scrolling all the way back down re-arms auto-follow
 * without needing a separate "jump to bottom" control.
 */
class TerminalWidget(
    /** Backing scrollback. Owned by the caller; this class only reads it. */
    val model: TerminalModel,
    /** Pixel width every row, the title bar and the prompt are built at. */
    private val contentWidth: Int,
    /** Row pitch in pixels. Also becomes [ScrollNode.stepPx] on [pane]. */
    private val rowHeightPx: Int = TextMetrics.FONT_LINE_HEIGHT_PX,
    private val scrollBarWidth: Int = 6,
    /** Must be >= 16 -- `titleBar`'s own fill sprite is 16px tall. */
    private val titleBarHeight: Int = 16,
    /** Inset from whatever [SurfaceNode] [build] is attached under -- keeps content off a frame's border. */
    private val padding: Int = 0,
) {
    /** True once the user has scrolled away from the bottom. See class KDoc. */
    var scrolledAway: Boolean = false
        private set

    /** The scroll pane from the most recent [build] call. */
    lateinit var pane: ScrollNode
        private set

    /** `scrollPx` of the pane [build] is about to replace. See class KDoc. */
    private var lastScrollPx: Int = 0

    /**
     * Re-derive [scrolledAway] from [pane]'s current offset.
     *
     * Called automatically by the scroll-notch and thumb-drag handlers
     * [build] installs. Call it yourself only if you move `pane.scrollPx`
     * through some other path, or [scrolledAway] goes stale.
     */
    fun noteUserScroll() {
        scrolledAway = pane.scrollPx < pane.maxScroll()
    }

    /**
     * Build the terminal's tree and attach it under [root].
     *
     * [onScrollChanged] fires whenever a scroll notch or thumb drag actually
     * moved [pane] — the caller's cue to re-run
     * [io.schemat.displaykit.surface.Surface.paintTree] and push a repaint,
     * WITHOUT rebuilding the tree again (exactly `PickerWindow.repaintTree`'s
     * job for its grid). This module (core) has no `SurfaceHost` to call
     * directly, so that push is always the caller's responsibility.
     *
     * Must be followed by [afterLayout] once the caller's
     * `Surface.layout { }` call returns, so the stick-to-bottom rule can see
     * this build's real, placed geometry.
     */
    fun build(
        root: SurfaceNode,
        title: String,
        promptHint: String,
        onClose: () -> Unit,
        onScrollChanged: () -> Unit = {}
    ) {
        if (::pane.isInitialized) lastScrollPx = pane.scrollPx

        val column = FlexNode(
            "terminal", FlexDirection.COLUMN,
            crossAxis = CrossAxis.STRETCH, gap = 2
        )
        column.padding = PxPadding.all(padding)

        val titleNode = WidgetNode("terminal-title", PxSize(contentWidth, titleBarHeight)) { p, r ->
            p.titleBar(r, title, onClose)
        }
        column.addChild(titleNode)

        val body = FlexNode("terminal-body", FlexDirection.ROW, gap = 4)
        body.flexGrow = 1

        val newPane = TerminalScrollNode("terminal-scroll")
        newPane.stepPx = rowHeightPx
        newPane.flexGrow = 1
        newPane.onEvent = { e ->
            if (e is SurfaceEvent.Scroll && newPane.scrollBy(e.delta)) {
                noteUserScroll()
                onScrollChanged()
                EventResult.CONSUMED
            } else EventResult.PASS
        }
        for ((index, line) in model.lines().withIndex()) {
            val row = WidgetNode("terminal-row-$index", PxSize(contentWidth, rowHeightPx)) { p, r ->
                p.label(line, r.x, r.y)
            }
            // Fixed, not left to intrinsic sizing: every row MUST measure to
            // exactly rowHeightPx, matching stepPx below, or ScrollNode's
            // whole-children-only emission (see its KDoc) silently drops rows
            // whose measured height drifted even by one pixel.
            row.height = rowHeightPx
            newPane.addChild(row)
        }
        body.addChild(newPane)

        val barWrap = FlexNode("terminal-scrollbar-wrap", FlexDirection.COLUMN)
        val bar = WidgetNode("terminal-scrollbar", PxSize(scrollBarWidth, 0)) { p, r ->
            p.scrollTrack(r)
            val max = newPane.maxScroll()
            val thumbH = thumbHeightFor(r.h, max)
            val rawY = if (max == 0) 0 else (newPane.scrollPx * (r.h - thumbH)) / max
            val thumbY = r.y + rawY.coerceIn(0, (r.h - thumbH).coerceAtLeast(0))
            p.scrollThumb(Rect(r.x, thumbY, scrollBarWidth, thumbH))
        }
        bar.width = scrollBarWidth
        bar.flexGrow = 1
        bar.onGrabMove = { _, y ->
            val r = bar.rect()
            val max = newPane.maxScroll()
            val thumbH = thumbHeightFor(r.h, max)
            val span = (r.h - thumbH).coerceAtLeast(1)
            val fraction = ((y - r.y).toDouble() / span).coerceIn(0.0, 1.0)
            if (newPane.scrollTo((fraction * max).toInt())) {
                noteUserScroll()
                onScrollChanged()
            }
        }
        barWrap.addChild(bar)
        body.addChild(barWrap)

        column.addChild(body)

        val promptNode = WidgetNode("terminal-prompt", PxSize(contentWidth, rowHeightPx)) { p, r ->
            p.label("> $promptHint", r.x, r.y)
        }
        promptNode.height = rowHeightPx
        column.addChild(promptNode)

        root.addChild(column)
        pane = newPane
    }

    /**
     * Re-pin [pane] to the bottom if the user hasn't scrolled away.
     *
     * Must run AFTER the caller's `Surface.layout { }` call returns —
     * [build] runs inside that call's builder lambda, before
     * `SurfaceNode.measure`/`place` give [pane] a real `maxScroll()`, so
     * doing this inside [build] itself would always see a max of zero.
     */
    fun afterLayout() {
        val target = if (scrolledAway) lastScrollPx else pane.maxScroll()
        pane.scrollTo(target)
    }

    /**
     * Scrollbar thumb height for a track of [trackH] px given [max] scroll.
     *
     * Same shape as `PickerWindow`'s private `thumbHeightFor` — duplicated
     * rather than shared, since promoting it to a common home is outside
     * this feature's scope and the function is small enough that the
     * duplication costs little.
     */
    private fun thumbHeightFor(trackH: Int, max: Int): Int =
        if (max == 0) trackH else maxOf(32, trackH * trackH / (trackH + max))
}
