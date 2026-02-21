package io.schemat.displaykit.page

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement
import io.schemat.displaykit.ui.elements.PanelElement

class DividerElement(
    ui: FloatingUI,
    private val container: SplitContainer,
    private val direction: SplitDirection,
    private val bounds: PageBounds
) : UIElement(
    ui = ui,
    localOffset = Vec3d(bounds.centerX.toDouble(), bounds.centerY.toDouble(), 0.0),
    isInteractive = true, hitboxSize = 0.0,
    hitboxWidth = bounds.width.toDouble(), hitboxHeight = bounds.height.toDouble()
) {
    companion object {
        const val Z_OFFSET = -0.05
    }

    var isDragging = false
        private set

    private var panel: PanelElement? = null
    private val normalMaterial = BlockStateRef.GRAY_CONCRETE
    private val hoverMaterial = BlockStateRef.LIGHT_GRAY_CONCRETE
    private val draggingMaterial = BlockStateRef.YELLOW_CONCRETE

    init {
        this.onClick = {
            isDragging = !isDragging
            updateAppearance()
        }
    }

    override fun spawn() {
        panel = ui.addPanel(
            offsetRight = bounds.centerX.toDouble(), offsetUp = bounds.centerY.toDouble(),
            offsetForward = Z_OFFSET, width = bounds.width, height = bounds.height,
            material = normalMaterial
        )
    }

    override fun destroy() {
        panel?.let { it.destroy(); ui.removeElement(it) }
        panel = null
    }

    override fun onHoverChanged() { updateAppearance() }

    fun update() {
        if (!isDragging) return
        val mousePos = ui.getMousePositionOnPlane() ?: return
        val (mouseX, mouseY) = mousePos
        val containerBounds = container.bounds
        val newRatio = when (direction) {
            SplitDirection.HORIZONTAL -> ((mouseX - containerBounds.x) / containerBounds.width).toFloat()
            SplitDirection.VERTICAL -> ((containerBounds.y - mouseY) / containerBounds.height).toFloat()
        }
        container.setSplitRatio(newRatio)
    }

    private fun updateAppearance() {
        panel?.let {
            val material = when {
                isDragging -> draggingMaterial
                isHovered -> hoverMaterial
                else -> normalMaterial
            }
            destroy()
            panel = ui.addPanel(
                offsetRight = bounds.centerX.toDouble(), offsetUp = bounds.centerY.toDouble(),
                offsetForward = Z_OFFSET, width = bounds.width, height = bounds.height,
                material = material
            )
        }
    }

    fun stopDragging() { isDragging = false; updateAppearance() }
}
