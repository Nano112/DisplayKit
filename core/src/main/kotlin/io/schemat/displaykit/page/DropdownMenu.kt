package io.schemat.displaykit.page

import io.schemat.displaykit.layout.CrossAxisAlignment
import io.schemat.displaykit.layout.Layout
import io.schemat.displaykit.layout.Padding
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement
import io.schemat.displaykit.ui.elements.PanelElement

data class DropdownItem(
    val id: String,
    val label: String,
    val icon: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

class DropdownMenu(
    private val ui: FloatingUI,
    private val anchorX: Float,
    private val anchorY: Float,
    private val items: List<DropdownItem>,
    private val onDismiss: () -> Unit
) {
    companion object {
        const val ITEM_HEIGHT = 0.22f
        const val ITEM_WIDTH = 1.2f
        const val PADDING = 0.04f
        const val ITEM_GAP = 0.02f
        const val Z_OFFSET = -0.15
    }

    private var backgroundPanel: PanelElement? = null
    private val elements = mutableListOf<UIElement>()
    private var isShown = false
    private var layout: Layout? = null

    fun show() {
        if (isShown || items.isEmpty()) return
        isShown = true

        val menuWidth = ITEM_WIDTH + PADDING * 2
        val menuHeight = items.size * ITEM_HEIGHT + (items.size - 1) * ITEM_GAP + PADDING * 2

        layout = Layout(width = menuWidth, height = menuHeight).apply {
            column("root", padding = Padding.all(PADDING), gap = ITEM_GAP, crossAxisAlignment = CrossAxisAlignment.Stretch) {
                items.forEachIndexed { index, _ -> leaf("item_$index", height = ITEM_HEIGHT) }
            }
            compute()
        }

        val menuBounds = PageBounds(x = anchorX - menuWidth, y = anchorY, width = menuWidth, height = menuHeight)
        val renderer = LayoutRenderer(ui, layout!!, menuBounds)

        backgroundPanel = ui.addPanel(
            offsetRight = menuBounds.centerX.toDouble(), offsetUp = menuBounds.centerY.toDouble(),
            offsetForward = Z_OFFSET, width = menuWidth, height = menuHeight, material = BlockStateRef.BLACK_CONCRETE
        )

        items.forEachIndexed { index, item ->
            val pos = renderer.getElementPosition("item_$index") ?: return@forEachIndexed
            val displayText = if (item.icon != null) "${item.icon} ${item.label}" else item.label

            val panel = ui.addInteractivePanel(
                offsetRight = pos.uiX, offsetUp = pos.uiY, offsetForward = Z_OFFSET - 0.01,
                width = pos.width, height = pos.height,
                material = BlockStateRef.BLACK_CONCRETE,
                hoverMaterial = if (item.enabled) BlockStateRef.GRAY_CONCRETE else null
            ) { if (item.enabled) { dismiss(); item.onClick() } }
            elements.add(panel)

            val label = ui.addAlignedLabel(
                offsetRight = pos.uiX, offsetUp = pos.uiY, offsetForward = Z_OFFSET - 0.02,
                text = displayText, scale = 0.25f
            )
            elements.add(label)
        }
    }

    fun dismiss() {
        if (!isShown) return
        isShown = false; destroy(); onDismiss()
    }

    fun destroy() {
        backgroundPanel?.let { it.destroy(); ui.removeElement(it) }; backgroundPanel = null
        elements.forEach { it.destroy(); ui.removeElement(it) }; elements.clear()
        layout = null
    }

    fun isShown(): Boolean = isShown
}
