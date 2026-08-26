package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.surface.BlockButton
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.blockButton
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode

data class ActionButtonStyle(
    val width: Int = BlockButton.widthFor(BlockButton.MIN_WIDTH),
    val base: BlockStateRef = BlockStateRef("minecraft:polished_blackstone"),
    val selectedBase: BlockStateRef = BlockStateRef("minecraft:gilded_blackstone"),
    val disabledBase: BlockStateRef = BlockStateRef.GRAY_CONCRETE,
    val baseThickness: Float = 0.0625f
) {
    init {
        require(width >= BlockButton.MIN_WIDTH) { "Action button must fit the vanilla button sprite." }
        require(baseThickness > 0f) { "Action button thickness must be positive." }
    }
}

/** A stable, state-driven surface button with sprite face and block volume. */
class ActionButtonView(
    id: String,
    private val label: () -> String,
    private val visible: () -> Boolean = { true },
    private val enabled: () -> Boolean = { true },
    private val selected: () -> Boolean = { false },
    val style: ActionButtonStyle = ActionButtonStyle(),
    private val base: () -> BlockStateRef = { style.base },
    private val isHovered: (String) -> Boolean,
    private val onClick: () -> Unit
) {
    val node = WidgetNode(id, PxSize(style.width, BlockButton.HEIGHT)) { painter, rect ->
        if (!visible()) return@WidgetNode
        val active = enabled()
        val chosen = selected()
        painter.blockButton(
            id = id,
            rect = rect,
            text = label(),
            state = when {
                !active -> BlockButton.State.SELECTED
                chosen && isHovered(id) -> BlockButton.State.SELECTED_HOVERED
                chosen -> BlockButton.State.SELECTED
                isHovered(id) -> BlockButton.State.HOVERED
                else -> BlockButton.State.NORMAL
            },
            base = when {
                !active -> style.disabledBase
                chosen -> style.selectedBase
                else -> base()
            },
            baseThickness = style.baseThickness
        )
    }.also { node ->
        node.onEvent = event@ { event ->
            if (event !is SurfaceEvent.Click || !visible() || !enabled()) {
                return@event EventResult.PASS
            }
            onClick()
            EventResult.CONSUMED
        }
    }
}
