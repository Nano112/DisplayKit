package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement

class TextInputElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    val width: Float = 2.0f,
    val height: Float = 0.3f,
    var placeholder: String = "Click to edit...",
    var value: String = "",
    val maxLength: Int = 50,
    val onValueChange: (String) -> Unit = {}
) : UIElement(
    ui = ui, localOffset = localOffset,
    isInteractive = true, hitboxSize = 0.0,
    hitboxWidth = width.toDouble(), hitboxHeight = height.toDouble()
) {
    private var backgroundPanel: VirtualBlockDisplay? = null
    private var textDisplay: VirtualTextDisplay? = null

    init {
        this.onClick = {
            ui.platform.textInput.requestInput(ui.owner, value) { result ->
                if (result != null) {
                    updateValue(result)
                }
            }
        }
    }

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)

        // Background
        val bg = VirtualBlockDisplay().apply {
            position = pos
            blockState = BlockStateRef.GRAY_CONCRETE
            brightness = Brightness.FULL
            transformation = ui.buildUIElementMatrix(
                localOffsetX = -width / 2, localOffsetY = -height / 2, localOffsetZ = 0f,
                scaleX = width, scaleY = height, scaleZ = 0.02f
            )
        }
        backgroundPanel = bg
        spawnEntity(bg)

        // Text
        val text = VirtualTextDisplay().apply {
            position = pos
            this.text = getDisplayText()
            billboard = Billboard.FIXED
            backgroundColor = DkColor.TRANSPARENT
            isSeeThrough = false
            brightness = Brightness.FULL
            textAlignment = TextAlignment.CENTER
            transformation = ui.buildUIElementMatrix(
                localOffsetX = 0f, localOffsetY = 0f, localOffsetZ = 0.03f,
                scaleX = 0.4f, scaleY = 0.4f
            )
        }
        textDisplay = text
        spawnEntity(text)
    }

    private fun getDisplayText(): TextComponent {
        return if (value.isEmpty()) {
            TextComponent(text = placeholder, color = DkColor.GRAY)
        } else {
            TextComponent.of(value)
        }
    }

    override fun destroy() {
        destroyAllEntities()
        backgroundPanel = null
        textDisplay = null
    }

    override fun onHoverChanged() {
        backgroundPanel?.let { display ->
            display.blockState = if (isHovered) BlockStateRef.LIGHT_GRAY_CONCRETE else BlockStateRef.GRAY_CONCRETE
            updateEntity(display)
        }
    }

    fun updateValue(newValue: String) {
        value = newValue.take(maxLength)
        textDisplay?.let { display ->
            display.text = getDisplayText()
            updateEntity(display)
        }
        onValueChange(value)
    }
}
