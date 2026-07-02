package io.schemat.displaykit.region

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3f
import org.joml.Quaternionf
import java.util.UUID

/**
 * Manages block display entities for an animated region.
 *
 * Implementations handle entity lifecycle (spawn/destroy) and animation.
 * Two approaches:
 * - Virtual entities (packet-only, per-viewer) — for previews / per-player views
 * - Real entities (server-side, visible to all) — for transitions everyone should see
 */
interface RegionDisplayManager {
    val capture: RegionCapture
    val isSpawned: Boolean
    val displayCount: Int

    /** Spawn block displays (with interior culling). */
    fun spawn()

    /** Destroy all block displays. */
    fun destroy()

    /**
     * Set target transforms for all blocks in one operation.
     * Client GPU interpolates over [durationTicks] — zero packets during playback.
     *
     * @param targetPosition Target world position
     * @param targetRotation Target rotation
     * @param targetScale Target scale
     * @param durationTicks Client-side interpolation duration in ticks
     */
    fun animateTransform(
        targetPosition: Vec3d,
        targetRotation: Quaternionf = Quaternionf(),
        targetScale: Vec3f = Vec3f(1f, 1f, 1f),
        durationTicks: Int
    )

    /** Add a viewer (only meaningful for virtual entity implementations). */
    fun addViewer(uuid: UUID) {}

    /** Remove a viewer (only meaningful for virtual entity implementations). */
    fun removeViewer(uuid: UUID) {}

    /** Get current viewer UUIDs. */
    fun getViewers(): Set<UUID> = emptySet()

    /** Set visibility of all displays (binary show/hide via view_range). */
    fun setVisibility(visible: Boolean) {}
}
