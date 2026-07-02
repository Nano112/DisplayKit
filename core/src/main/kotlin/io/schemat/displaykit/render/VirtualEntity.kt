package io.schemat.displaykit.render

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3f

enum class EntityType {
    BLOCK_DISPLAY,
    TEXT_DISPLAY,
    ITEM_DISPLAY
}

abstract class VirtualEntity(
    val entityId: Int = EntityIdAllocator.next(),
    val entityType: EntityType
) {
    var position: Vec3d = Vec3d.ZERO
        set(value) { field = value; dirty = true }

    var transformation: Mat4f = Mat4f.identity()
        set(value) { field = value; dirty = true }

    var translation: Vec3f = Vec3f.ZERO
        set(value) { field = value; dirty = true }

    var scale: Vec3f = Vec3f(1f, 1f, 1f)
        set(value) { field = value; dirty = true }

    var billboard: Billboard = Billboard.FIXED
        set(value) { field = value; dirty = true }

    var brightness: Brightness? = null
        set(value) { field = value; dirty = true }

    var viewRange: Float = 1.0f
        set(value) { field = value; dirty = true }

    var glowColorOverride: DkColor? = null
        set(value) { field = value; dirty = true }

    /**
     * Entity-level GLOWING shared flag (outline shader). Required for
     * [glowColorOverride] to actually show — the override only picks the
     * outline color.
     */
    var glowing: Boolean = false
        set(value) { field = value; dirty = true }

    var interpolationDuration: Int = 0
        set(value) { field = value; dirty = true }

    var startInterpolation: Int = 0
        set(value) { field = value; dirty = true }

    var dirty: Boolean = false

    fun markClean() { dirty = false }
}

class VirtualBlockDisplay(
    entityId: Int = EntityIdAllocator.next()
) : VirtualEntity(entityId, EntityType.BLOCK_DISPLAY) {

    var blockState: BlockStateRef = BlockStateRef.STONE
        set(value) { field = value; dirty = true }
}

class VirtualTextDisplay(
    entityId: Int = EntityIdAllocator.next()
) : VirtualEntity(entityId, EntityType.TEXT_DISPLAY) {

    var text: TextComponent = TextComponent.EMPTY
        set(value) { field = value; dirty = true }

    var backgroundColor: DkColor = DkColor.TRANSPARENT
        set(value) { field = value; dirty = true }

    var textAlignment: TextAlignment = TextAlignment.CENTER
        set(value) { field = value; dirty = true }

    var lineWidth: Int = 200
        set(value) { field = value; dirty = true }

    var isSeeThrough: Boolean = false
        set(value) { field = value; dirty = true }

    var textOpacity: Byte = (-1).toByte() // 0xFF = fully opaque
        set(value) { field = value; dirty = true }

    var hasShadow: Boolean = true
        set(value) { field = value; dirty = true }
}

/**
 * Virtual item display entity for 3D models.
 * Used for custom models via CustomModelData on items.
 */
class VirtualItemDisplay(
    entityId: Int = EntityIdAllocator.next()
) : VirtualEntity(entityId, EntityType.ITEM_DISPLAY) {

    /** The item ID (e.g., "minecraft:leather_horse_armor") */
    var itemId: String = "minecraft:leather_horse_armor"
        set(value) { field = value; dirty = true }

    /** CustomModelData value for model override selection */
    var customModelData: Int = 0
        set(value) { field = value; dirty = true }

    /** Item color (for leather armor and similar colorable items) */
    var itemColor: DkColor? = null
        set(value) { field = value; dirty = true }

    /** Display transform type */
    var itemDisplayTransform: ItemDisplayTransform = ItemDisplayTransform.FIXED
        set(value) { field = value; dirty = true }
}

/**
 * Item display transform modes.
 */
enum class ItemDisplayTransform {
    NONE,
    THIRDPERSON_LEFTHAND,
    THIRDPERSON_RIGHTHAND,
    FIRSTPERSON_LEFTHAND,
    FIRSTPERSON_RIGHTHAND,
    HEAD,
    GUI,
    GROUND,
    FIXED
}
