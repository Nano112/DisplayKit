package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement

class CheckboxElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    var checked: Boolean = false,
    val label: String? = null,
    val onToggle: (Boolean) -> Unit = {}
) : UIElement(ui, localOffset, isInteractive = true, hitboxSize = 0.3) {

    private var boxDisplay: VirtualBlockDisplay? = null
    private var checkmarkDisplay: VirtualBlockDisplay? = null
    private var labelDisplay: VirtualTextDisplay? = null

    private val boxSize = 0.2f

    init {
        this.onClick = {
            checked = !checked
            updateAppearance()
            onToggle(checked)
        }
    }

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)

        // Checkbox box
        val box = VirtualBlockDisplay().apply {
            position = pos
            blockState = BlockStateRef.LIGHT_GRAY_CONCRETE
            brightness = Brightness.FULL
            transformation = ui.buildUIElementMatrix(
                localOffsetX = -boxSize / 2, localOffsetY = -boxSize / 2, localOffsetZ = 0f,
                scaleX = boxSize, scaleY = boxSize, scaleZ = 0.02f
            )
        }
        boxDisplay = box
        spawnEntity(box)

        // Checkmark
        val checkmark = VirtualBlockDisplay().apply {
            position = pos
            blockState = BlockStateRef.LIME_CONCRETE
            brightness = Brightness.FULL
            transformation = ui.buildUIElementMatrix(
                localOffsetX = -boxSize / 2 * 0.6f, localOffsetY = -boxSize / 2 * 0.6f, localOffsetZ = 0.02f,
                scaleX = boxSize * 0.6f, scaleY = boxSize * 0.6f, scaleZ = 0.04f
            )
            viewRange = if (checked) 1.0f else 0.0f
        }
        checkmarkDisplay = checkmark
        spawnEntity(checkmark)

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
                    localOffsetX = boxSize / 2 + 0.4f, localOffsetY = 0f, localOffsetZ = 0.02f,
                    scaleX = 0.4f, scaleY = 0.4f
                )
            }
            labelDisplay = lbl
            spawnEntity(lbl)
        }

        updateAppearance()
    }

    override fun destroy() {
        destroyAllEntities()
        boxDisplay = null
        checkmarkDisplay = null
        labelDisplay = null
    }

    override fun onHoverChanged() {
        updateAppearance()
    }

    private fun updateAppearance() {
        boxDisplay?.let { display ->
            display.blockState = if (isHovered) BlockStateRef.WHITE_CONCRETE else BlockStateRef.LIGHT_GRAY_CONCRETE
            updateEntity(display)
        }
        checkmarkDisplay?.let { display ->
            display.viewRange = if (checked) 1.0f else 0.0f
            updateEntity(display)
        }
    }

    fun updateChecked(newChecked: Boolean) {
        checked = newChecked
        updateAppearance()
        onToggle(checked)
    }
}
