package io.schemat.displaykit.ui

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.VirtualEntity

abstract class UIElement(
    val ui: FloatingUI,
    var localOffset: Vec3d,
    val isInteractive: Boolean = false,
    val hitboxSize: Double = 0.3,
    val hitboxWidth: Double = 0.0,
    val hitboxHeight: Double = 0.0
) {
    var isHovered = false
    var onClick: (() -> Unit)? = null

    /**
     * Multiplier applied to the hitbox during hover/click tests. In-world
     * clicking is imprecise, so hitboxes are slightly forgiving by default.
     */
    var hitMargin: Double = 1.2

    private var lastInteractTime = 0L
    private val interactCooldown = 300L

    protected val entities = mutableListOf<VirtualEntity>()

    fun usesRectangularHitbox(): Boolean = hitboxWidth > 0 && hitboxHeight > 0

    abstract fun spawn()
    abstract fun destroy()
    abstract fun onHoverChanged()

    open fun getWorldPosition(): Vec3d {
        return ui.localToWorld(localOffset.x, localOffset.y, localOffset.z)
    }

    fun canInteract(): Boolean {
        return System.currentTimeMillis() - lastInteractTime > interactCooldown
    }

    fun markInteracted() {
        lastInteractTime = System.currentTimeMillis()
    }

    protected fun spawnEntity(entity: VirtualEntity) {
        entities.add(entity)
        ui.spawnEntity(entity)
    }

    protected fun despawnEntity(entity: VirtualEntity) {
        entities.remove(entity)
        ui.despawnEntity(entity)
    }

    protected fun updateEntity(entity: VirtualEntity) {
        ui.updateEntity(entity)
    }

    protected fun teleportEntity(entity: VirtualEntity) {
        ui.teleportEntity(entity)
    }

    fun destroyAllEntities() {
        if (entities.isNotEmpty()) {
            ui.despawnEntities(entities)
            entities.clear()
        }
    }
}
