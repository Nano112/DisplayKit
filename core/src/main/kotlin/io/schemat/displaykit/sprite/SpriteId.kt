package io.schemat.displaykit.sprite

/**
 * Stable key for a sprite within a texture atlas.
 *
 * [atlas] is the bare atlas name as it appears under
 * `assets/minecraft/atlases/` — "gui", "blocks", "items" — not a namespaced id.
 */
data class SpriteId(val atlas: String, val sprite: String) {
    override fun toString(): String = "$atlas/$sprite"
}
