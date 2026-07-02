package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement
import kotlin.math.roundToInt

class SliderElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    val width: Float = 2.0f,
    var minValue: Float = 0f,
    var maxValue: Float = 100f,
    var value: Float = 50f,
    val step: Float = 1f,
    val showValue: Boolean = true,
    val label: String? = null,
    val trackHeight: Float = 0.1f,
    val handleWidth: Float = 0.15f,
    val handleHeight: Float = 0.15f,
    val onValueChange: (Float) -> Unit = {}
) : UIElement(
    ui = ui, localOffset = localOffset,
    isInteractive = true, hitboxSize = 0.0,
    hitboxWidth = width.toDouble(), hitboxHeight = 0.3
) {
    private var trackPanel: VirtualBlockDisplay? = null
    private var handlePanel: VirtualBlockDisplay? = null
    private var labelDisplay: VirtualTextDisplay? = null
    private var valueDisplay: VirtualTextDisplay? = null
    private var isDragging = false

    init {
        this.onClick = {
            isDragging = !isDragging
            updateAppearance()
        }
    }

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)

        // Track
        val track = VirtualBlockDisplay().apply {
            position = pos
            blockState = BlockStateRef.GRAY_CONCRETE
            brightness = Brightness.FULL
            transformation = ui.buildUIElementMatrix(
                localOffsetX = -width / 2, localOffsetY = -trackHeight / 2, localOffsetZ = 0f,
                scaleX = width, scaleY = trackHeight, scaleZ = 0.02f
            )
        }
        trackPanel = track
        spawnEntity(track)

        // Handle
        val handleX = getHandleXPosition()
        val handlePos = ui.calculatePosition(localOffset.x + handleX, localOffset.y, localOffset.z)
        val handle = VirtualBlockDisplay().apply {
            position = handlePos
            blockState = BlockStateRef.LIGHT_BLUE_CONCRETE
            brightness = Brightness.FULL
            transformation = ui.buildUIElementMatrix(
                localOffsetX = -handleWidth / 2, localOffsetY = -handleHeight / 2, localOffsetZ = -0.01f,
                scaleX = handleWidth, scaleY = handleHeight, scaleZ = 0.04f
            )
        }
        handlePanel = handle
        spawnEntity(handle)

        // Label
        if (label != null) {
            val lbl = VirtualTextDisplay().apply {
                position = pos
                text = TextComponent.of(label)
                billboard = Billboard.FIXED
                backgroundColor = DkColor.TRANSPARENT
                isSeeThrough = true
                brightness = Brightness.FULL
                textAlignment = TextAlignment.CENTER
                transformation = ui.buildUIElementMatrix(
                    localOffsetX = -width / 2 - 0.3f, localOffsetY = 0f, localOffsetZ = 0.02f,
                    scaleX = 0.35f, scaleY = 0.35f
                )
            }
            labelDisplay = lbl
            spawnEntity(lbl)
        }

        // Value display
        if (showValue) {
            val valDisp = VirtualTextDisplay().apply {
                position = pos
                text = formatValueText()
                billboard = Billboard.FIXED
                backgroundColor = DkColor.TRANSPARENT
                isSeeThrough = true
                brightness = Brightness.FULL
                textAlignment = TextAlignment.CENTER
                transformation = ui.buildUIElementMatrix(
                    localOffsetX = width / 2 + 0.2f, localOffsetY = 0f, localOffsetZ = 0.02f,
                    scaleX = 0.35f, scaleY = 0.35f
                )
            }
            valueDisplay = valDisp
            spawnEntity(valDisp)
        }

        updateAppearance()
    }

    private fun getHandleXPosition(): Double {
        val normalizedValue = (value - minValue) / (maxValue - minValue)
        return (normalizedValue * width - width / 2).toDouble()
    }

    private fun formatValueText(): TextComponent {
        val formattedValue = if (step >= 1f) {
            value.roundToInt().toString()
        } else {
            String.format("%.2f", value)
        }
        return TextComponent.of(formattedValue)
    }

    override fun destroy() {
        destroyAllEntities()
        trackPanel = null
        handlePanel = null
        labelDisplay = null
        valueDisplay = null
    }

    override fun onHoverChanged() {
        updateAppearance()
    }

    private fun updateAppearance() {
        handlePanel?.let { display ->
            display.blockState = when {
                isDragging -> BlockStateRef.ORANGE_CONCRETE
                isHovered -> BlockStateRef.CYAN_CONCRETE
                else -> BlockStateRef.LIGHT_BLUE_CONCRETE
            }
            updateEntity(display)
        }
    }

    fun update() {
        if (!isDragging) return

        val mousePos = ui.getMousePositionOnPlane() ?: return
        val (mouseX, _) = mousePos

        val localX = mouseX - localOffset.x
        val normalizedX = ((localX + width / 2) / width).coerceIn(0.0, 1.0)
        val newValue = (minValue + normalizedX * (maxValue - minValue)).toFloat()

        val steppedValue = if (step > 0) {
            (newValue / step).roundToInt() * step
        } else {
            newValue
        }.coerceIn(minValue, maxValue)

        if (steppedValue != value) {
            value = steppedValue
            updateHandlePosition()
            valueDisplay?.let {
                it.text = formatValueText()
                updateEntity(it)
            }
            onValueChange(value)
        }
    }

    private fun updateHandlePosition() {
        val handleX = getHandleXPosition()
        handlePanel?.let { display ->
            display.position = ui.calculatePosition(localOffset.x + handleX, localOffset.y, localOffset.z)
            teleportEntity(display)
        }
    }

    fun updateSliderValue(newValue: Float) {
        value = newValue.coerceIn(minValue, maxValue)
        updateHandlePosition()
        valueDisplay?.let {
            it.text = formatValueText()
            updateEntity(it)
        }
        onValueChange(value)
    }

    fun isDragging() = isDragging
}
