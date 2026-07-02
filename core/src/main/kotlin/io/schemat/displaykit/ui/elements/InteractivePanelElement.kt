package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement

class InteractivePanelElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    val width: Float,
    val height: Float,
    val material: BlockStateRef,
    val hoverMaterial: BlockStateRef?,
    onClick: () -> Unit
) : UIElement(
    ui, localOffset,
    isInteractive = true,
    hitboxWidth = width.toDouble(),
    hitboxHeight = height.toDouble()
) {
    private var blockDisplay: VirtualBlockDisplay? = null

    init {
        this.onClick = onClick
    }

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)

        val display = VirtualBlockDisplay().apply {
            position = pos
            blockState = material
            brightness = Brightness(12, 12)
            transformation = ui.buildPanelMatrix(width, height, 0.02f)
        }
        blockDisplay = display
        spawnEntity(display)
    }

    override fun destroy() {
        destroyAllEntities()
        blockDisplay = null
    }

    override fun onHoverChanged() {
        if (hoverMaterial != null) {
            blockDisplay?.let { display ->
                display.blockState = if (isHovered) hoverMaterial else material
                updateEntity(display)
            }
        }
    }

    fun relocate(newOffsetX: Double, newOffsetY: Double, newOffsetZ: Double) {
        localOffset = Vec3d(newOffsetX, newOffsetY, newOffsetZ)
        blockDisplay?.let { display ->
            display.position = ui.calculatePosition(newOffsetX, newOffsetY, newOffsetZ)
            teleportEntity(display)
        }
    }
}
