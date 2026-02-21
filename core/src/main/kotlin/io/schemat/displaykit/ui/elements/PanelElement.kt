package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement

class PanelElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    val width: Float,
    val height: Float,
    val material: BlockStateRef,
    val rotateToFace: Boolean = true
) : UIElement(ui, localOffset, isInteractive = false) {

    private var blockDisplay: VirtualBlockDisplay? = null

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)

        val depth = if (rotateToFace) 0.02f else width.coerceAtLeast(height)

        val display = VirtualBlockDisplay().apply {
            position = pos
            blockState = material
            brightness = Brightness(12, 12)
            transformation = ui.buildPanelMatrix(width, height, depth)
        }
        blockDisplay = display
        spawnEntity(display)
    }

    override fun destroy() {
        destroyAllEntities()
        blockDisplay = null
    }

    override fun onHoverChanged() {}

    fun setMaterial(newMaterial: BlockStateRef) {
        blockDisplay?.let { display ->
            display.blockState = newMaterial
            updateEntity(display)
        }
    }
}
