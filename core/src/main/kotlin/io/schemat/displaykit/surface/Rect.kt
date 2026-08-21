package io.schemat.displaykit.surface

/**
 * An axis-aligned rectangle in canvas pixel space. Origin is top-left and y
 * grows downward, matching how sprites and UI are laid out rather than how
 * world coordinates run.
 */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
    fun contains(px: Int, py: Int): Boolean = px >= x && px < right && py >= y && py < bottom
}

/**
 * An interactive area on a surface, in draw order — later entries sit on top,
 * so hit-testing walks the list backwards.
 */
data class HitRect(val id: String, val rect: Rect, val onClick: () -> Unit)

/**
 * What a slot holds. Rendered as a separate item display in front of the plane,
 * because an item model cannot live inside a text component.
 */
data class ItemRef(val itemId: String, val customModelData: Int = 0, val count: Int = 1)
