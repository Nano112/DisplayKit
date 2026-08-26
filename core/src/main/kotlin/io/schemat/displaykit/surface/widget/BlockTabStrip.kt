package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.surface.BlockButton
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.NineSlicePainter
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode
import io.schemat.displaykit.surface.tab
import io.schemat.displaykit.surface.tabBox

/** Materials and sizing shared by a vertical strip of physical tabs. */
data class BlockTabStripStyle(
    val minWidth: Int = BlockButton.MIN_WIDTH,
    val normalBase: BlockStateRef = BlockStateRef("minecraft:polished_blackstone"),
    val selectedBase: BlockStateRef = BlockStateRef("minecraft:gilded_blackstone"),
    val baseThickness: Float = 0.1f
)

/**
 * A reusable, stateful vertical tab strip.
 *
 * The strip owns the renderer's row pitch, every button state, hover event
 * semantics, physical bases, and glyph preparation. The caller supplies only
 * values, labels, selection state, and a selection callback.
 */
class BlockTabStrip<T>(
    id: String,
    values: List<T>,
    selected: () -> T,
    label: (T) -> String,
    isHovered: (String) -> Boolean,
    onSelected: (T) -> Unit,
    style: BlockTabStripStyle = BlockTabStripStyle()
) {
    val node: FlexNode

    init {
        val width = BlockButton.widthFor(style.minWidth)
        val height = BlockButton.HEIGHT
        // An odd whole-row pitch makes every tab's text phase identical.
        val pitch = TextMetrics.centringHeight(height)
        node = FlexNode(id, FlexDirection.COLUMN, gap = pitch - height)
        node.width = width

        val tabs = mutableListOf<WidgetNode>()
        for (value in values) {
            val tabId = "$id-${label(value)}"
            val tab = WidgetNode(tabId, PxSize(width, height)) { painter, rect ->
                val active = value == selected()
                painter.tab(
                    tabId,
                    rect,
                    label(value),
                    selected = active,
                    hovered = isHovered(tabId),
                    base = if (active) style.selectedBase else style.normalBase,
                    baseThickness = style.baseThickness
                ) {}
            }
            tab.onEvent = { event ->
                when (event) {
                    is SurfaceEvent.Click -> {
                        onSelected(value)
                        EventResult.CONSUMED
                    }
                    is SurfaceEvent.PointerEnter, is SurfaceEvent.PointerExit ->
                        EventResult.CONSUMED
                    else -> EventResult.PASS
                }
            }
            tabs += tab
            node.addChild(tab)
        }

        // Prepare every visual state at the rect the tab helper will really
        // paint, after layout has placed the strip.
        node.onPrepare = {
            val states = BlockButton.statesFor()
            for (tab in tabs) {
                val box = tabBox(tab.rect())
                for (sprite in states) NineSlicePainter.prewarm(sprite, box)
            }
        }
    }
}
