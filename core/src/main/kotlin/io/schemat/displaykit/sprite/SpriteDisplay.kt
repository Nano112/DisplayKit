package io.schemat.displaykit.sprite

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.Billboard
import io.schemat.displaykit.render.Brightness
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextComponent
import io.schemat.displaykit.render.VirtualTextDisplay

/**
 * World-quad render mode: one sprite as an oriented quad in the world, at
 * exact block-pixel scale.
 *
 * Costs one entity per quad. For flat 2D content prefer [SpriteCanvas], which
 * composes many sprites into a single entity.
 *
 * Billboard defaults to [Billboard.FIXED] so callers can orient quads freely —
 * a sprite used as a cube face must not swivel toward the viewer.
 */
object SpriteDisplay {

    fun create(
        entry: SpriteEntry,
        position: Vec3d,
        scale: Float = 1f,
        billboard: Billboard = Billboard.FIXED
    ): VirtualTextDisplay = VirtualTextDisplay().also { display ->
        display.position = position
        display.billboard = billboard
        display.backgroundColor = DkColor.TRANSPARENT
        display.brightness = Brightness.FULL
        display.hasShadow = false
        display.isSeeThrough = false
        applyTo(display, entry, scale)
    }

    /** Point an existing display at [entry] and rescale it. */
    fun applyTo(display: VirtualTextDisplay, entry: SpriteEntry, scale: Float = 1f) {
        display.text = TextComponent.sprite(entry.id.atlas, entry.id.sprite)
        display.transformation = SpriteGeometry.transform(entry, scale)
    }
}
