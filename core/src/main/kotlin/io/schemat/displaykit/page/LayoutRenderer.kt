package io.schemat.displaykit.page

import io.schemat.displaykit.layout.Layout
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.elements.*

class LayoutRenderer(
    private val ui: FloatingUI,
    private val layout: Layout,
    private val pageBounds: PageBounds
) {
    fun layoutToUI(layoutX: Float, layoutY: Float, width: Float, height: Float): Pair<Double, Double> {
        val layoutCenterX = layoutX + width / 2
        val layoutCenterY = layoutY + height / 2
        val uiX = pageBounds.x + layoutCenterX
        val uiY = pageBounds.y - layoutCenterY
        return Pair(uiX.toDouble(), uiY.toDouble())
    }

    fun getElementPosition(elementId: String): ElementPosition? {
        val result = layout.getResult(elementId) ?: return null
        val absPos = layout.getAbsolutePosition(elementId) ?: return null
        val (uiX, uiY) = layoutToUI(absPos.x, absPos.y, result.width, result.height)
        return ElementPosition(uiX = uiX, uiY = uiY, width = result.width, height = result.height)
    }

    fun renderPanel(elementId: String, material: BlockStateRef, zOffset: Double = 0.0): PanelElement? {
        val pos = getElementPosition(elementId) ?: return null
        return ui.addPanel(
            offsetRight = pos.uiX, offsetUp = pos.uiY, offsetForward = zOffset,
            width = pos.width, height = pos.height, material = material
        )
    }

    fun renderLabel(elementId: String, text: String, scale: Float = 0.35f, zOffset: Double = -0.05): LabelElement? {
        val pos = getElementPosition(elementId) ?: return null
        return ui.addLabel(offsetRight = pos.uiX, offsetUp = pos.uiY, offsetForward = zOffset, text = text, scale = scale)
    }

    fun renderAlignedLabel(elementId: String, text: String, scale: Float = 0.4f, zOffset: Double = -0.03): AlignedLabelElement? {
        val pos = getElementPosition(elementId) ?: return null
        return ui.addAlignedLabel(offsetRight = pos.uiX, offsetUp = pos.uiY, offsetForward = zOffset, text = text, scale = scale)
    }

    fun renderButton(
        elementId: String, label: String?, material: BlockStateRef, hoverMaterial: BlockStateRef,
        zOffset: Double = -0.02, onClick: () -> Unit
    ): ButtonElement? {
        val pos = getElementPosition(elementId) ?: return null
        val size = minOf(pos.width, pos.height)
        return ui.addButton(
            offsetRight = pos.uiX, offsetUp = pos.uiY, offsetForward = zOffset,
            label = label, material = material, hoverMaterial = hoverMaterial, size = size, onClick = onClick
        )
    }

    data class ElementPosition(val uiX: Double, val uiY: Double, val width: Float, val height: Float)
}
