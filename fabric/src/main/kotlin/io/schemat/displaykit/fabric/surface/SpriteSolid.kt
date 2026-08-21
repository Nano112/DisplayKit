package io.schemat.displaykit.fabric.surface

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.pack.SpriteSolidProvider
import io.schemat.displaykit.render.Brightness
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.ItemDisplayTransform
import io.schemat.displaykit.render.VirtualItemDisplay
import io.schemat.displaykit.sprite.SpriteEntry
import org.joml.Matrix4f

/**
 * A sprite rendered as a physically thick slab, for a control that should be
 * genuinely proud of its panel rather than painted to look it.
 *
 * Registers the model on first use; the caller must rebuild the pack before the
 * client can see it.
 */
object SpriteSolid {

    fun create(
        entry: SpriteEntry,
        position: Vec3d,
        thicknessPx: Int = 1,
        scale: Float = 1f,
        tint: DkColor? = null
    ): VirtualItemDisplay {
        val cmd = SpriteSolidProvider.register(entry, thicknessPx)
        return VirtualItemDisplay().also { d ->
            d.position = position
            // Must be the item SpriteSolidProvider writes its item definition
            // for, or the CustomModelData case never applies.
            d.itemId = SpriteSolidProvider.BASE_ITEM     // tintable base item
            d.customModelData = cmd
            d.itemColor = tint
            d.itemDisplayTransform = ItemDisplayTransform.FIXED
            d.brightness = Brightness.FULL
            d.transformation = Mat4f(Matrix4f().scale(scale, scale, scale))
        }
    }
}
