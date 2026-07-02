package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Mat4f
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

    private fun transformFor(content: String): Mat4f =
        // Center-anchor: text_display blocks are bottom-anchored natively.
        ui.buildUIElementMatrix(
            localOffsetX = 0f,
            localOffsetY = TextMetrics.verticalCenterCorrection(content, scale),
            localOffsetZ = 0f,
            scaleX = scale, scaleY = scale, scaleZ = scale
        )

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)

        val labelText = this.text
        val display = VirtualTextDisplay().apply {
            position = pos
            text = TextComponent.of(labelText)
            billboard = Billboard.FIXED
            backgroundColor = this@AlignedLabelElement.backgroundColor
            isSeeThrough = false
            brightness = Brightness.FULL
            textAlignment = TextAlignment.CENTER
            viewRange = 1.0f
            transformation = transformFor(labelText)
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
            display.transformation = transformFor(newText)
            updateEntity(display)
        }
    }
}
