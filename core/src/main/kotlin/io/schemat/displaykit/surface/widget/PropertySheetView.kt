package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.property.PropertyChangeResult
import io.schemat.displaykit.property.PropertyField
import io.schemat.displaykit.property.PropertySheetModel
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.surface.BlockButton
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.blockButton
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode

data class PropertySheetViewStyle(
    val width: Int = BlockButton.widthFor(BlockButton.MIN_WIDTH),
    val gap: Int = 2,
    val fieldGap: Int = 6,
    val buttonBase: BlockStateRef = BlockStateRef("minecraft:polished_blackstone"),
    val disabledBase: BlockStateRef = BlockStateRef("minecraft:gray_concrete"),
    val baseThickness: Float = 0.0625f,
    val labelColor: DkColor? = null,
    val valueColor: DkColor? = null
) {
    init {
        require(width >= BlockButton.MIN_WIDTH) { "Property sheet width must fit the vanilla button sprite." }
        require(gap >= 0 && fieldGap >= 0) { "Property sheet gaps cannot be negative." }
        require(baseThickness > 0f) { "Property button thickness must be positive." }
    }
}

/** Stable primitive composition for a renderer-neutral [PropertySheetModel]. */
class PropertySheetView(
    id: String,
    val model: PropertySheetModel,
    private val isHovered: (String) -> Boolean,
    private val onChanged: (PropertyField) -> Unit,
    val style: PropertySheetViewStyle = PropertySheetViewStyle()
) {
    val node = FlexNode(id, FlexDirection.COLUMN, gap = style.fieldGap)

    init {
        node.width = style.width
        model.fields.forEach { field ->
            val group = FlexNode("$id-field-${field.id}", FlexDirection.COLUMN, gap = style.gap)
            group.width = style.width
            group.addChild(readoutNode("$id-${field.id}-value", field))
            group.addChild(changeNode("$id-${field.id}-previous", field, previous = true))
            group.addChild(changeNode("$id-${field.id}-next", field, previous = false))
            node.addChild(group)
        }
    }

    private fun readoutNode(nodeId: String, field: PropertyField) = WidgetNode(
        nodeId,
        PxSize(style.width, TextMetrics.FONT_LINE_HEIGHT_PX)
    ) { painter, rect ->
        val y = TextMetrics.rowAlignedY(rect.y)
        val value = TextMetrics.ellipsize(field.valueText, style.width / 2)
        val labelWidth = (style.width - TextMetrics.textWidthPx(value) - 6).coerceAtLeast(0)
        painter.label(TextMetrics.ellipsize(field.label, labelWidth), rect.x, y, style.labelColor)
        painter.label(value, rect.right - TextMetrics.textWidthPx(value), y, style.valueColor)
    }

    private fun changeNode(nodeId: String, field: PropertyField, previous: Boolean): WidgetNode {
        val node = WidgetNode(nodeId, PxSize(style.width, BlockButton.HEIGHT)) { painter, rect ->
            val label = if (previous) field.previousLabel else field.nextLabel
            if (label == null) return@WidgetNode
            val enabled = if (previous) field.canPrevious else field.canNext
            painter.blockButton(
                id = nodeId,
                rect = rect,
                text = label,
                state = when {
                    !enabled -> BlockButton.State.SELECTED
                    isHovered(nodeId) -> BlockButton.State.HOVERED
                    else -> BlockButton.State.NORMAL
                },
                base = if (enabled) style.buttonBase else style.disabledBase,
                baseThickness = style.baseThickness
            )
        }
        node.onEvent = event@ { event ->
            if (event !is SurfaceEvent.Click) return@event EventResult.PASS
            if (previous && field.previousLabel == null) return@event EventResult.PASS
            val result = if (previous) field.previous() else field.next()
            if (result == PropertyChangeResult.CHANGED) onChanged(field)
            EventResult.CONSUMED
        }
        return node
    }
}
