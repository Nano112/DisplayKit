package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement
import kotlin.math.roundToInt

class ProgressBarElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    val width: Float = 2.0f,
    val height: Float = 0.15f,
    var progress: Float = 0f,
    val showPercentage: Boolean = true,
    val label: String? = null,
    val barColor: BlockStateRef = BlockStateRef.LIME_CONCRETE,
    val backgroundColor: BlockStateRef = BlockStateRef.GRAY_CONCRETE
) : UIElement(ui, localOffset, isInteractive = false) {

    private var backgroundPanel: VirtualBlockDisplay? = null
    private var progressPanel: VirtualBlockDisplay? = null
    private var labelDisplay: VirtualTextDisplay? = null
    private var percentageDisplay: VirtualTextDisplay? = null

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)

        // Background
        val bg = VirtualBlockDisplay().apply {
            position = pos
            blockState = backgroundColor
            brightness = Brightness.FULL
            transformation = ui.buildUIElementMatrix(
                localOffsetX = -width / 2, localOffsetY = -height / 2, localOffsetZ = 0f,
                scaleX = width, scaleY = height, scaleZ = 0.02f
            )
        }
        backgroundPanel = bg
        spawnEntity(bg)

        // Progress bar
        val prog = VirtualBlockDisplay().apply {
            position = pos
            blockState = barColor
            brightness = Brightness.FULL
            updateProgressTransform(this)
        }
        progressPanel = prog
        spawnEntity(prog)

        // Label
        if (label != null) {
            val lbl = VirtualTextDisplay().apply {
                position = pos
                text = TextComponent.of(label)
                billboard = Billboard.FIXED
                this.backgroundColor = DkColor.TRANSPARENT
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

        // Percentage
        if (showPercentage) {
            val pct = VirtualTextDisplay().apply {
                position = pos
                text = formatPercentageText()
                billboard = Billboard.FIXED
                this.backgroundColor = DkColor.TRANSPARENT
                isSeeThrough = true
                brightness = Brightness.FULL
                textAlignment = TextAlignment.CENTER
                transformation = ui.buildUIElementMatrix(
                    localOffsetX = 0f, localOffsetY = 0f, localOffsetZ = 0.05f,
                    scaleX = 0.35f, scaleY = 0.35f
                )
            }
            percentageDisplay = pct
            spawnEntity(pct)
        }
    }

    private fun updateProgressTransform(display: VirtualBlockDisplay) {
        val progressWidth = width * progress.coerceIn(0f, 1f)
        display.transformation = ui.buildUIElementMatrix(
            localOffsetX = -width / 2, localOffsetY = -height / 2, localOffsetZ = 0.03f,
            scaleX = progressWidth.coerceAtLeast(0.001f), scaleY = height, scaleZ = 0.01f
        )
    }

    private fun formatPercentageText(): TextComponent {
        val percentage = (progress * 100).roundToInt()
        return TextComponent.of("$percentage%")
    }

    override fun destroy() {
        destroyAllEntities()
        backgroundPanel = null
        progressPanel = null
        labelDisplay = null
        percentageDisplay = null
    }

    override fun onHoverChanged() {}

    fun updateProgress(newProgress: Float) {
        progress = newProgress.coerceIn(0f, 1f)
        progressPanel?.let {
            updateProgressTransform(it)
            updateEntity(it)
        }
        percentageDisplay?.let {
            it.text = formatPercentageText()
            updateEntity(it)
        }
    }

    fun incrementProgress(delta: Float) {
        updateProgress(progress + delta)
    }
}
