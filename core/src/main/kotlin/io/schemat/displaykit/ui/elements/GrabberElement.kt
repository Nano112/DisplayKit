package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement

class GrabberElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    val material: BlockStateRef,
    val hoverMaterial: BlockStateRef,
    val size: Float,
    val initiallyVisible: Boolean,
    onClick: () -> Unit
) : UIElement(ui, localOffset, isInteractive = true, hitboxSize = size.toDouble() * 2.0) {

    private var blockDisplay: VirtualBlockDisplay? = null
    var isVisible: Boolean = initiallyVisible
        private set
    var isToggled: Boolean = false
        private set

    // Mutable position for dragging
    private var currentOffset: Vec3d = localOffset

    init {
        this.onClick = {
            isToggled = !isToggled
            updateAppearance()
            onClick()
        }
    }

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)

        val display = VirtualBlockDisplay().apply {
            position = pos
            blockState = material
            brightness = Brightness.FULL
            transformation = ui.buildUIElementMatrix(
                localOffsetX = -size / 2, localOffsetY = -size / 2, localOffsetZ = -size / 2,
                scaleX = size, scaleY = size, scaleZ = size
            )
            viewRange = if (initiallyVisible) 1.0f else 0.0f
        }
        blockDisplay = display
        spawnEntity(display)
    }

    override fun destroy() {
        destroyAllEntities()
        blockDisplay = null
    }

    override fun onHoverChanged() {
        if (!isVisible) return
        updateAppearance()
    }

    private fun updateAppearance() {
        blockDisplay?.let { display ->
            display.blockState = when {
                isToggled -> BlockStateRef.ORANGE_CONCRETE
                isHovered -> hoverMaterial
                else -> material
            }
            updateEntity(display)
        }
    }

    fun setVisible(visible: Boolean) {
        isVisible = visible
        blockDisplay?.let { display ->
            display.viewRange = if (visible) 1.0f else 0.0f
            updateEntity(display)
        }
    }

    fun toggle() {
        isToggled = !isToggled
        updateAppearance()
    }

    fun setPosition(newOffsetRight: Double, newOffsetUp: Double) {
        currentOffset = Vec3d(newOffsetRight, newOffsetUp, currentOffset.z)
        blockDisplay?.let { display ->
            display.position = ui.calculatePosition(newOffsetRight, newOffsetUp, currentOffset.z)
            teleportEntity(display)
        }
    }
}
