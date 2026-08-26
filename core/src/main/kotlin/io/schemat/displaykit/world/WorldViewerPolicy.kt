package io.schemat.displaykit.world

import io.schemat.displaykit.math.Vec3d
import java.util.UUID

/** Minimal viewer data needed by reusable audience policies. */
data class WorldViewer(val id: UUID, val position: Vec3d)

/** Selects the audience for a world layer independently from its contents. */
fun interface WorldViewerPolicy {
    fun select(candidates: Collection<WorldViewer>): Set<UUID>
}

object WorldViewerPolicies {
    @JvmStatic
    fun fixed(viewers: Collection<UUID>): WorldViewerPolicy {
        val snapshot = viewers.toSet()
        return WorldViewerPolicy { snapshot }
    }

    @JvmStatic
    fun owner(owner: UUID): WorldViewerPolicy = fixed(setOf(owner))

    @JvmStatic
    @JvmOverloads
    fun radius(
        anchor: () -> Vec3d?,
        radius: Double,
        horizontalOnly: Boolean = false,
        predicate: (WorldViewer) -> Boolean = { true },
    ): WorldViewerPolicy {
        require(radius > 0.0 && radius.isFinite()) { "Viewer radius must be finite and positive" }
        val radiusSquared = radius * radius
        return WorldViewerPolicy { candidates ->
            val origin = anchor() ?: return@WorldViewerPolicy emptySet()
            candidates.asSequence()
                .filter(predicate)
                .filter { viewer ->
                    val dx = viewer.position.x - origin.x
                    val dz = viewer.position.z - origin.z
                    val distance = if (horizontalOnly) dx * dx + dz * dz
                    else {
                        val dy = viewer.position.y - origin.y
                        dx * dx + dy * dy + dz * dz
                    }
                    distance <= radiusSquared
                }
                .map(WorldViewer::id)
                .toSet()
        }
    }

    @JvmStatic
    fun predicate(predicate: (WorldViewer) -> Boolean): WorldViewerPolicy =
        WorldViewerPolicy { candidates -> candidates.filter(predicate).mapTo(linkedSetOf(), WorldViewer::id) }
}
