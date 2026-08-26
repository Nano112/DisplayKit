package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.NineSlicePainter
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.scrollThumb
import io.schemat.displaykit.surface.scrollThumbHeight
import io.schemat.displaykit.surface.scrollTrack
import io.schemat.displaykit.surface.snapToLinePitch
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.ScrollNode
import io.schemat.displaykit.surface.layout.WidgetNode

data class VerticalScrollViewStyle(
    val scrollbarWidth: Int = 6,
    val gap: Int = 10
)

/**
 * A scroll pane and its scrollbar as one reusable interaction primitive.
 *
 * It owns wheel dispatch, thumb sizing/painting, inverse drag mapping, layout,
 * and glyph preparation. A caller cannot accidentally draw one thumb height
 * and drag against another, or prepare a different Y phase than it paints.
 */
class VerticalScrollView(
    id: String,
    val pane: ScrollNode,
    private val possibleMaxScrolls: () -> Collection<Int> = { listOf(pane.maxScroll()) },
    private val onScrollChanged: () -> Unit,
    style: VerticalScrollViewStyle = VerticalScrollViewStyle()
) {
    val node = FlexNode(id, FlexDirection.ROW, gap = style.gap)
    val scrollbar: WidgetNode

    init {
        node.flexGrow = 1
        pane.flexGrow = 1
        pane.onEvent = { event ->
            if (event is SurfaceEvent.Scroll && pane.scrollBy(event.delta)) {
                onScrollChanged()
                EventResult.CONSUMED
            } else EventResult.PASS
        }
        node.addChild(pane)

        val wrap = FlexNode("$id-scrollbar-wrap", FlexDirection.COLUMN)
        scrollbar = WidgetNode(
            "$id-scrollbar",
            PxSize(style.scrollbarWidth, 0)
        ) { painter, rect ->
            painter.scrollTrack(rect)
            val max = pane.maxScroll()
            val thumbH = scrollThumbHeight(rect.h, max)
            val rawY = if (max == 0) 0 else
                (pane.scrollPx * (rect.h - thumbH)) / max
            val thumbY = rect.y + rawY.coerceIn(0, (rect.h - thumbH).coerceAtLeast(0))
            painter.scrollThumb(Rect(rect.x, thumbY, rect.w, thumbH))
        }
        scrollbar.width = style.scrollbarWidth
        scrollbar.flexGrow = 1
        scrollbar.onGrabMove = { _, y ->
            val rect = scrollbar.rect()
            val max = pane.maxScroll()
            val thumbH = scrollThumbHeight(rect.h, max)
            val span = (rect.h - thumbH).coerceAtLeast(1)
            val fraction = ((y - rect.y).toDouble() / span).coerceIn(0.0, 1.0)
            if (pane.scrollTo((fraction * max).toInt())) onScrollChanged()
        }
        scrollbar.onPrepare = {
            val sprite = SpriteIndex.bundled.get(SCROLL_THUMB)
            val rect = scrollbar.rect()
            if (sprite != null && rect.h > 0) {
                // scrollThumb snaps absolute Y; probe one complete row above
                // the minimum so short nine-slice crops always have a legal
                // fallback row in GlyphPlacement.
                val probeY = snapToLinePitch(rect.y) + TextMetrics.FONT_LINE_HEIGHT_PX
                for (max in possibleMaxScrolls()) {
                    val thumbH = scrollThumbHeight(rect.h, max)
                    NineSlicePainter.prewarm(
                        sprite,
                        Rect(0, probeY, style.scrollbarWidth, thumbH)
                    )
                }
            }
        }
        wrap.addChild(scrollbar)
        node.addChild(wrap)
    }

    companion object {
        private val SCROLL_THUMB = SpriteId("gui", "widget/scroller")
    }
}
