package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.Billboard
import io.schemat.displaykit.render.Brightness
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.sprite.SpriteCanvas
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement
import org.joml.Matrix4f

/**
 * A composited sprite canvas as a single UI element — one entity regardless of
 * how many sprites the canvas holds.
 *
 * [scale] converts the canvas's pixel grid to world units the same way
 * [LabelElement] scales text: a bare `VirtualTextDisplay` renders at
 * `TextMetrics.PIXEL_SIZE` world units per pixel (transformation scale 1),
 * which is normally far too wide for a UI-scale element — callers pick
 * [scale] to hit a target on-screen size.
 */
class SpriteCanvasElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    private var canvas: SpriteCanvas,
    private val scale: Float
) : UIElement(ui, localOffset, isInteractive = false) {

    private var display: VirtualTextDisplay? = null

    private fun transformFor(): Mat4f =
        Mat4f(Matrix4f().scale(scale, scale, scale))

    override fun spawn() {
        val d = VirtualTextDisplay().apply {
            position = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)
            text = canvas.toTextComponent()
            billboard = Billboard.CENTER
            backgroundColor = DkColor.TRANSPARENT
            brightness = Brightness.FULL
            hasShadow = false
            transformation = transformFor()
        }
        display = d
        spawnEntity(d)
    }

    /** Swap in a redrawn canvas without respawning the entity. */
    fun update(newCanvas: SpriteCanvas) {
        canvas = newCanvas
        display?.let {
            it.text = newCanvas.toTextComponent()
            updateEntity(it)
        }
    }

    override fun destroy() {
        destroyAllEntities()
        display = null
    }

    override fun onHoverChanged() {}
}
