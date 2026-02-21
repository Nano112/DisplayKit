package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement

class AlignedLabelElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    val text: String,
    val scale: Float,
    val backgroundColor: DkColor
) : UIElement(ui, localOffset, isInteractive = false) {

    private var textDisplay: VirtualTextDisplay? = null

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)

        val labelText = this.text
        val labelScale = this.scale
        val display = VirtualTextDisplay().apply {
            position = pos
            text = TextComponent.of(labelText)
            billboard = Billboard.FIXED
            backgroundColor = this@AlignedLabelElement.backgroundColor
            isSeeThrough = false
            brightness = Brightness.FULL
            textAlignment = TextAlignment.CENTER
            viewRange = 1.0f
            transformation = ui.buildUIElementMatrix(
                localOffsetX = 0f, localOffsetY = 0f, localOffsetZ = 0f,
                scaleX = labelScale, scaleY = labelScale, scaleZ = labelScale
            )
        }
        textDisplay = display
        spawnEntity(display)
    }

    override fun destroy() {
        destroyAllEntities()
        textDisplay = null
    }

    override fun onHoverChanged() {}

    fun updateText(newText: String) {
        textDisplay?.let { display ->
            display.text = TextComponent.of(newText)
            updateEntity(display)
        }
    }
}
