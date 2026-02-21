package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement
import org.joml.Matrix4f

class ButtonElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    val label: String?,
    val material: BlockStateRef,
    val hoverMaterial: BlockStateRef,
    val size: Float,
    onClick: () -> Unit
) : UIElement(ui, localOffset, isInteractive = true, hitboxSize = size.toDouble()) {

    private var blockDisplay: VirtualBlockDisplay? = null
    private var textDisplay: VirtualTextDisplay? = null

    init {
        this.onClick = onClick
    }

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)

        val block = VirtualBlockDisplay().apply {
            position = pos
            blockState = material
            brightness = Brightness.FULL
            transformation = ui.buildUIElementMatrix(
                localOffsetX = -size / 2, localOffsetY = -size / 2, localOffsetZ = -size / 2,
                scaleX = size, scaleY = size, scaleZ = size
            )
        }
        blockDisplay = block
        spawnEntity(block)

        if (label != null) {
            val textPos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z - (size / 2.0 + 0.05))

            val text = VirtualTextDisplay().apply {
                position = textPos
                this.text = TextComponent.of(label)
                billboard = Billboard.FIXED
                backgroundColor = DkColor.TRANSPARENT
                isSeeThrough = false
                brightness = Brightness.FULL
                textAlignment = TextAlignment.CENTER
                viewRange = 1.0f
                transformation = ui.buildUIElementMatrix(
                    localOffsetX = 0f, localOffsetY = 0f, localOffsetZ = 0f,
                    scaleX = 0.35f, scaleY = 0.35f, scaleZ = 0.35f
                )
            }
            textDisplay = text
            spawnEntity(text)
        }
    }

    override fun destroy() {
        destroyAllEntities()
        blockDisplay = null
        textDisplay = null
    }

    override fun onHoverChanged() {
        blockDisplay?.let { display ->
            display.blockState = if (isHovered) hoverMaterial else material
            updateEntity(display)
        }
    }
}
