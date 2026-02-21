package io.schemat.displaykit.render

import io.schemat.displaykit.math.Vec3d
import java.util.UUID

/**
 * Manages the "trigger entity" that activates post-processing for glassmorphism.
 *
 * How it works:
 * 1. When a glass panel is shown, we spawn an invisible armor stand with Glowing
 * 2. This triggers Minecraft's entity_outline post-effect pipeline
 * 3. Our overridden entity_outline.json includes blur passes
 * 4. When glass panels are hidden, the trigger entity is removed
 *
 * The trigger entity is:
 * - Invisible (no model rendered)
 * - Small (0.1 scale to minimize hitbox)
 * - Has Glowing effect
 * - Positioned just in front of the player's view
 * - Only visible to the specific player
 *
 * Usage:
 * ```kotlin
 * // Platform implementation spawns the trigger
 * val trigger = GlassTrigger.request(playerId)
 *
 * // When glass UI is closed
 * GlassTrigger.release(playerId)
 * ```
 */
object GlassTrigger {
    /**
     * Callback to spawn the trigger entity.
     * Set by the platform implementation (e.g., Fabric).
     *
     * Parameters: (playerId, position) -> entityId
     */
    var onSpawnTrigger: ((UUID, Vec3d) -> Int)? = null

    /**
     * Callback to despawn the trigger entity.
     * Parameters: (playerId, entityId)
     */
    var onDespawnTrigger: ((UUID, Int) -> Unit)? = null

    /**
     * Callback to update trigger position.
     * Parameters: (playerId, entityId, newPosition)
     */
    var onUpdatePosition: ((UUID, Int, Vec3d) -> Unit)? = null

    // Track active triggers per player
    private val activeTriggers = mutableMapOf<UUID, TriggerState>()

    /**
     * Request a glass trigger for a player.
     * Multiple requests are ref-counted - the trigger stays until all are released.
     *
     * @param playerId The player who needs glass effects
     * @param position Position for the trigger (typically in front of player)
     * @return The trigger state, or null if spawning failed
     */
    fun request(playerId: UUID, position: Vec3d): TriggerState? {
        val existing = activeTriggers[playerId]
        if (existing != null) {
            existing.refCount++
            return existing
        }

        val entityId = onSpawnTrigger?.invoke(playerId, position) ?: return null

        val state = TriggerState(
            playerId = playerId,
            entityId = entityId,
            position = position,
            refCount = 1
        )
        activeTriggers[playerId] = state
        return state
    }

    /**
     * Release a glass trigger request.
     * The trigger is only despawned when all requests are released.
     */
    fun release(playerId: UUID) {
        val state = activeTriggers[playerId] ?: return
        state.refCount--

        if (state.refCount <= 0) {
            onDespawnTrigger?.invoke(playerId, state.entityId)
            activeTriggers.remove(playerId)
        }
    }

    /**
     * Force release all triggers for a player (e.g., on disconnect).
     */
    fun releaseAll(playerId: UUID) {
        val state = activeTriggers.remove(playerId) ?: return
        onDespawnTrigger?.invoke(playerId, state.entityId)
    }

    /**
     * Update the trigger position (call each tick while glass is visible).
     */
    fun updatePosition(playerId: UUID, position: Vec3d) {
        val state = activeTriggers[playerId] ?: return
        if (state.position != position) {
            state.position = position
            onUpdatePosition?.invoke(playerId, state.entityId, position)
        }
    }

    /**
     * Check if a player has an active glass trigger.
     */
    fun isActive(playerId: UUID): Boolean = activeTriggers.containsKey(playerId)

    /**
     * Get the current trigger state for a player.
     */
    fun getState(playerId: UUID): TriggerState? = activeTriggers[playerId]

    /**
     * Clear all triggers (e.g., on shutdown).
     */
    fun clear() {
        activeTriggers.keys.toList().forEach { releaseAll(it) }
    }

    /**
     * Calculate optimal trigger position for a player.
     * Places the trigger slightly in front of and below eye level.
     */
    fun calculatePosition(eyePos: Vec3d, lookDir: Vec3d): Vec3d {
        // Place 2 blocks in front, 0.5 blocks below eye level
        return eyePos + lookDir * 2.0 + Vec3d(0.0, -0.5, 0.0)
    }
}

/**
 * State of an active glass trigger.
 */
data class TriggerState(
    val playerId: UUID,
    val entityId: Int,
    var position: Vec3d,
    var refCount: Int
)
