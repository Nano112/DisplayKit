package io.schemat.displaykit.page

import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement
import io.schemat.displaykit.ui.elements.ButtonElement

class PageChrome(
    private val page: Page,
    private val ui: FloatingUI
) {
    companion object {
        const val CHROME_HEIGHT = 0.25f
        const val BUTTON_SIZE = 0.15f
        const val BUTTON_GAP = 0.04f
        const val CORNER_PADDING = 0.06f
        const val Z_OFFSET = -0.03
    }

    private var closeButton: ButtonElement? = null
    private var optionsButton: ButtonElement? = null
    private var dropdown: DropdownMenu? = null
    private val elements = mutableListOf<UIElement>()

    fun render(bounds: PageBounds) {
        val closeX = bounds.right - CORNER_PADDING - BUTTON_SIZE / 2
        val closeY = bounds.y - CORNER_PADDING - BUTTON_SIZE / 2

        closeButton = ui.addButton(
            offsetRight = closeX.toDouble(), offsetUp = closeY.toDouble(),
            offsetForward = Z_OFFSET, label = "\u2715",
            material = BlockStateRef.RED_CONCRETE, hoverMaterial = BlockStateRef.ORANGE_CONCRETE,
            size = BUTTON_SIZE
        ) { page.close() }
        elements.add(closeButton!!)

        val optionsX = closeX - BUTTON_SIZE - BUTTON_GAP
        val optionsY = closeY

        optionsButton = ui.addButton(
            offsetRight = optionsX.toDouble(), offsetUp = optionsY.toDouble(),
            offsetForward = Z_OFFSET, label = "\u22EE",
            material = BlockStateRef.GRAY_CONCRETE, hoverMaterial = BlockStateRef.LIGHT_GRAY_CONCRETE,
            size = BUTTON_SIZE
        ) { toggleDropdown(optionsX, optionsY - BUTTON_SIZE / 2 - 0.05f) }
        elements.add(optionsButton!!)
    }

    private fun toggleDropdown(anchorX: Float, anchorY: Float) {
        if (dropdown?.isShown() == true) { dropdown?.dismiss(); dropdown = null; return }
        val items = page.getDropdownOptions()
        if (items.isEmpty()) return
        dropdown = DropdownMenu(ui = ui, anchorX = anchorX, anchorY = anchorY, items = items, onDismiss = { dropdown = null })
        dropdown?.show()
    }

    fun dismissDropdown() { dropdown?.dismiss(); dropdown = null }

    fun destroy() {
        dismissDropdown()
        elements.forEach { it.destroy(); ui.removeElement(it) }
        elements.clear()
        closeButton = null; optionsButton = null
    }
}
