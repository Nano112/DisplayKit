package io.schemat.displaykit.ui

import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.platform.TaskHandle
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.TextComponent
import io.schemat.displaykit.ui.elements.*
import kotlin.math.abs

class ResizableWhiteboard(
    private val ui: FloatingUI,
    private val player: PlayerRef,
    initialWidth: Float = 3.0f,
    initialHeight: Float = 2.0f,
    var showDemoComponents: Boolean = false
) {
    var width: Float = initialWidth
        private set
    var height: Float = initialHeight
        private set

    private var resizeModeEnabled = false
    private var activeGrabber: GrabberCorner? = null
    private var updateTask: TaskHandle? = null
    private var tickCount = 0

    private var whiteboard: PanelElement? = null
    private val frame = mutableListOf<PanelElement>()
    private val grabbers = mutableMapOf<GrabberCorner, GrabberElement>()

    private val demoComponents = mutableListOf<UIElement>()
    private var demoProgressBar: ProgressBarElement? = null

    enum class GrabberCorner {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private val frameThickness = 0.08f
    private val frameDepth = 0.02f

    init {
        rebuild()
        startUpdateTask()
    }

    private fun rebuild() {
        whiteboard?.let { ui.removeElement(it) }
        frame.forEach { ui.removeElement(it) }
        frame.clear()
        grabbers.values.forEach { ui.removeElement(it) }
        grabbers.clear()
        demoComponents.forEach { ui.removeElement(it) }
        demoComponents.clear()

        val halfW = width / 2.0
        val halfH = height / 2.0

        whiteboard = ui.addPanel(
            offsetRight = 0.0, offsetUp = 0.0,
            offsetForward = (frameDepth * 2).toDouble(),
            width = width, height = height,
            material = BlockStateRef.WHITE_CONCRETE, rotateToFace = true
        )

        // Top frame
        frame.add(ui.addPanel(
            offsetRight = 0.0, offsetUp = halfH + frameThickness / 2,
            offsetForward = frameDepth.toDouble(),
            width = width, height = frameThickness,
            material = BlockStateRef("minecraft:obsidian"), rotateToFace = true
        ))

        // Bottom frame
        frame.add(ui.addPanel(
            offsetRight = 0.0, offsetUp = -halfH - frameThickness / 2,
            offsetForward = frameDepth.toDouble(),
            width = width, height = frameThickness,
            material = BlockStateRef("minecraft:obsidian"), rotateToFace = true
        ))

        // Left frame
        frame.add(ui.addPanel(
            offsetRight = -halfW - frameThickness / 2,
            offsetUp = 0.0, offsetForward = frameDepth.toDouble(),
            width = frameThickness, height = height + frameThickness * 2,
            material = BlockStateRef.LIME_CONCRETE, rotateToFace = true
        ))

        // Right frame
        frame.add(ui.addPanel(
            offsetRight = halfW + frameThickness / 2,
            offsetUp = 0.0, offsetForward = frameDepth.toDouble(),
            width = frameThickness, height = height + frameThickness * 2,
            material = BlockStateRef("minecraft:magenta_concrete"), rotateToFace = true
        ))

        // Corner grabbers
        grabbers[GrabberCorner.TOP_LEFT] = ui.addGrabber(
            offsetRight = -halfW, offsetUp = halfH, offsetForward = -0.05,
            material = BlockStateRef.LIGHT_BLUE_CONCRETE,
            hoverMaterial = BlockStateRef.CYAN_CONCRETE,
            size = 0.15f, visible = resizeModeEnabled
        ) { toggleGrabber(GrabberCorner.TOP_LEFT) }

        grabbers[GrabberCorner.TOP_RIGHT] = ui.addGrabber(
            offsetRight = halfW, offsetUp = halfH, offsetForward = -0.05,
            material = BlockStateRef.LIGHT_BLUE_CONCRETE,
            hoverMaterial = BlockStateRef.CYAN_CONCRETE,
            size = 0.15f, visible = resizeModeEnabled
        ) { toggleGrabber(GrabberCorner.TOP_RIGHT) }

        grabbers[GrabberCorner.BOTTOM_LEFT] = ui.addGrabber(
            offsetRight = -halfW, offsetUp = -halfH, offsetForward = -0.05,
            material = BlockStateRef.LIGHT_BLUE_CONCRETE,
            hoverMaterial = BlockStateRef.CYAN_CONCRETE,
            size = 0.15f, visible = resizeModeEnabled
        ) { toggleGrabber(GrabberCorner.BOTTOM_LEFT) }

        grabbers[GrabberCorner.BOTTOM_RIGHT] = ui.addGrabber(
            offsetRight = halfW, offsetUp = -halfH, offsetForward = -0.05,
            material = BlockStateRef.LIGHT_BLUE_CONCRETE,
            hoverMaterial = BlockStateRef.CYAN_CONCRETE,
            size = 0.15f, visible = resizeModeEnabled
        ) { toggleGrabber(GrabberCorner.BOTTOM_RIGHT) }

        if (showDemoComponents) {
            addDemoComponents(halfW, halfH)
        }
    }

    private fun addDemoComponents(halfW: Double, halfH: Double) {
        demoComponents.add(ui.addCheckbox(
            offsetRight = -halfW + 0.5, offsetUp = halfH - 0.5,
            offsetForward = -0.05, checked = false, label = "Enable feature"
        ) { checked ->
            player.sendMessage(TextComponent.of("Checkbox: $checked"))
        })

        demoComponents.add(ui.addSlider(
            offsetRight = 0.0, offsetUp = halfH - 0.8,
            offsetForward = -0.05, width = width * 0.7f,
            minValue = 0f, maxValue = 100f, value = 50f,
            step = 5f, showValue = true, label = "Volume"
        ) { value ->
            player.sendMessage(TextComponent.of("Slider: $value"))
        })

        demoProgressBar = ui.addProgressBar(
            offsetRight = 0.0, offsetUp = halfH - 1.1,
            offsetForward = -0.05, width = width * 0.7f,
            height = 0.12f, progress = 0.0f,
            showPercentage = true, label = "Loading"
        )
        demoComponents.add(demoProgressBar!!)

        demoComponents.add(ui.addTextInput(
            offsetRight = 0.0, offsetUp = halfH - 1.5,
            offsetForward = -0.05, width = width * 0.7f,
            height = 0.25f, placeholder = "Enter text...",
            value = "", maxLength = 30
        ) { value ->
            player.sendMessage(TextComponent.of("Text: $value"))
        })
    }

    private fun toggleGrabber(corner: GrabberCorner) {
        if (activeGrabber == corner) {
            activeGrabber = null
            grabbers[corner]?.toggle()
            player.sendMessage(TextComponent.of("Released ${corner.name}"))
        } else {
            activeGrabber?.let { prev -> grabbers[prev]?.toggle() }
            activeGrabber = corner
            grabbers[corner]?.toggle()
            player.sendMessage(TextComponent.of("Grabbed ${corner.name} - Move your mouse to resize"))
        }
    }

    fun toggleResizeMode() {
        resizeModeEnabled = !resizeModeEnabled
        grabbers.values.forEach { it.setVisible(resizeModeEnabled) }

        if (!resizeModeEnabled) {
            activeGrabber?.let { prev -> grabbers[prev]?.toggle() }
            activeGrabber = null
        }

        val modeText = if (resizeModeEnabled) "ON" else "OFF"
        player.sendMessage(TextComponent.of("Resize mode: $modeText"))
    }

    private fun startUpdateTask() {
        updateTask = ui.platform.scheduler.scheduleRepeating(0L, 1L, Runnable { update() })
    }

    private fun update() {
        tickCount++

        if (showDemoComponents && demoProgressBar != null) {
            val progress = (tickCount % 100) / 100f
            demoProgressBar?.updateProgress(progress)
        }

        val corner = activeGrabber ?: return
        val mousePos = ui.getMousePositionOnPlane() ?: return
        val (mouseX, mouseY) = mousePos

        val minSize = 0.5f
        val maxSize = 10.0f

        val (newWidth, newHeight) = when (corner) {
            GrabberCorner.TOP_RIGHT -> {
                Pair((mouseX * 2).toFloat().coerceIn(minSize, maxSize),
                     (mouseY * 2).toFloat().coerceIn(minSize, maxSize))
            }
            GrabberCorner.TOP_LEFT -> {
                Pair((-mouseX * 2).toFloat().coerceIn(minSize, maxSize),
                     (mouseY * 2).toFloat().coerceIn(minSize, maxSize))
            }
            GrabberCorner.BOTTOM_RIGHT -> {
                Pair((mouseX * 2).toFloat().coerceIn(minSize, maxSize),
                     (-mouseY * 2).toFloat().coerceIn(minSize, maxSize))
            }
            GrabberCorner.BOTTOM_LEFT -> {
                Pair((-mouseX * 2).toFloat().coerceIn(minSize, maxSize),
                     (-mouseY * 2).toFloat().coerceIn(minSize, maxSize))
            }
        }

        if (abs(newWidth - width) > 0.1f || abs(newHeight - height) > 0.1f) {
            width = newWidth
            height = newHeight
            rebuild()
        }
    }

    fun destroy() {
        updateTask?.cancel()
    }
}
