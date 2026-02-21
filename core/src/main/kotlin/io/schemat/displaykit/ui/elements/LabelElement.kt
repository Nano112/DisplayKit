package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement
import org.joml.Matrix4f

class LabelElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    val text: String,
    val scale: Float,
    val backgroundColor: DkColor
) : UIElement(ui, localOffset, isInteractive = false) {

    private var textDisplay: VirtualTextDisplay? = null

    override fun spawn() {
        val textPos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z - 0.1)

        val labelText = this.text
        val labelScale = this.scale
        val display = VirtualTextDisplay().apply {
            position = textPos
            text = TextComponent.of(labelText)
            billboard = Billboard.CENTER
            backgroundColor = this@LabelElement.backgroundColor
            isSeeThrough = false
            brightness = Brightness.FULL
            textAlignment = TextAlignment.CENTER
            viewRange = 1.0f
            transformation = Mat4f(Matrix4f().scaling(labelScale, labelScale, labelScale))
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
