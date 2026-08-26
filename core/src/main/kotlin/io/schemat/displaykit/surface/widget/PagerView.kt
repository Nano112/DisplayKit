package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.layout.CrossAxis
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.MainAxis
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode

data class PagerStyle(
    val width: Int,
    val controlSize: Int = 32,
    val previousSprite: SpriteId = SpriteId("gui", "spectator/scroll_left"),
    val nextSprite: SpriteId = SpriteId("gui", "spectator/scroll_right"),
    val hoveredTint: DkColor = DkColor.fromRGB(255, 214, 92),
    val disabledTint: DkColor = DkColor.fromRGB(83, 89, 99)
) {
    init {
        require(width >= controlSize * 2 + 24) { "Pager width is too small for both controls and status." }
        require(controlSize > 0) { "Pager control size must be positive." }
    }
}

/** Compact sprite-arrow paging controls for lists, grids and arbitrary pages. */
class PagerView(
    id: String,
    private val pageIndex: () -> Int,
    private val pageCount: () -> Int,
    private val visible: () -> Boolean = { true },
    private val isHovered: (String) -> Boolean,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    val style: PagerStyle
) {
    private val previous = SpriteIndex.bundled.get(style.previousSprite)
    private val next = SpriteIndex.bundled.get(style.nextSprite)
    val node = FlexNode(
        id,
        FlexDirection.ROW,
        mainAxis = MainAxis.SPACE_BETWEEN,
        crossAxis = CrossAxis.CENTER
    ).also { row ->
        row.width = style.width
        row.height = style.controlSize
        row.addChild(control("$id-previous", previous, { pageIndex() > 0 }, onPrevious))
        row.addChild(
            WidgetNode("$id-status", PxSize(48, TextMetrics.FONT_LINE_HEIGHT_PX)) { painter, rect ->
                if (!visible()) return@WidgetNode
                val value = "${pageIndex() + 1}/${pageCount()}"
                painter.label(
                    value,
                    rect.centeredX(TextMetrics.textWidthPx(value)),
                    TextMetrics.rowAlignedY(rect.y)
                )
            }
        )
        row.addChild(control("$id-next", next, { pageIndex() + 1 < pageCount() }, onNext))
        row.onPrepare = {
            previous?.let(SpriteGlyphs::warmAllPhases)
            next?.let(SpriteGlyphs::warmAllPhases)
        }
    }

    private fun control(
        nodeId: String,
        sprite: io.schemat.displaykit.sprite.SpriteEntry?,
        enabled: () -> Boolean,
        action: () -> Unit
    ): WidgetNode = WidgetNode(nodeId, PxSize(style.controlSize, style.controlSize)) { painter, rect ->
        if (!visible() || sprite == null) return@WidgetNode
        painter.elevate {
            faceIcon(
                sprite,
                rect.centeredX(sprite.width),
                rect.centeredY(sprite.height),
                tint = when {
                    !enabled() -> style.disabledTint
                    isHovered(nodeId) -> style.hoveredTint
                    else -> null
                }
            )
        }
    }.also { node ->
        node.onEvent = event@ { event ->
            if (event !is SurfaceEvent.Click || !visible() || !enabled()) {
                return@event EventResult.PASS
            }
            action()
            EventResult.CONSUMED
        }
    }
}
