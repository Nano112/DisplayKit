package io.schemat.displaykit.ui

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3f
import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.platform.TaskHandle
import io.schemat.displaykit.render.*
import java.util.UUID
import java.util.logging.Logger
import kotlin.math.abs
import kotlin.math.floor

data class OverlayDebugInfo(
    val eyePos: Vec3d,
    val lookDir: Vec3d,
    val hitPos: Vec3d?,       // where the ray hits the ground plane (null = no hit)
    val hitT: Double,         // ray parameter (distance along look)
    val hoveredKey: String?,  // currently hovered cell key
    val entityCount: Int,     // total active entities
    val interactiveCount: Int // interactive entities only
)

data class OverlayCell(
    val worldX: Double,
    val worldY: Double,
    val worldZ: Double,
    val cellSize: Float,
    val material: BlockStateRef,
    val hoverMaterial: BlockStateRef? = null,
    val interactive: Boolean = false
)

class WorldOverlay(
    val platform: PlatformProvider,
    val owner: PlayerRef,
    private val updateIntervalTicks: Long = 10
) {
    private val logger = Logger.getLogger("displaykit/overlay")
    private val activeEntities = java.util.concurrent.ConcurrentHashMap<String, OverlayEntity>()
    private var tickTask: TaskHandle? = null
    private var tickCount = 0
    private var isDestroyed = false
    private var hoveredKey: String? = null

    var cellProvider: ((playerPos: Vec3d) -> Map<String, OverlayCell>)? = null
    var onCellClick: ((key: String, isRightClick: Boolean) -> Unit)? = null

    private class OverlayEntity(
        val entity: VirtualBlockDisplay,
        var cell: OverlayCell
    )

    fun start() {
        tickTask = platform.scheduler.scheduleRepeating(1L, 1L, Runnable {
            if (isDestroyed) return@Runnable
            tick()
        })
    }

    /** Force an immediate cell update (e.g. after state change). */
    fun forceUpdate() {
        if (!isDestroyed) updateCells()
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        tickTask?.cancel()

        val ids = activeEntities.values.map { it.entity.entityId }
        if (ids.isNotEmpty()) {
            platform.packetSender.destroyEntities(ids, setOf(owner.uuid))
        }
        activeEntities.clear()
    }

    fun isDestroyed() = isDestroyed
    fun isHovered() = hoveredKey != null

    /** Fresh raycast check — accurate at call time, not dependent on tick cache. */
    fun isLookingAtCell(): Boolean {
        try {
            if (activeEntities.isEmpty()) return false
            val eyePos = owner.eyePosition()
            val lookDir = owner.lookDirection()
            return findHoveredCell(eyePos, lookDir) != null
        } catch (e: Exception) {
            return false
        }
    }

    fun handleClick(isRightClick: Boolean) {
        try {
            // Fresh raycast, falling back to tick-cached hoveredKey (handles thread race)
            val eyePos = owner.eyePosition()
            val lookDir = owner.lookDirection()
            val freshKey = findHoveredCell(eyePos, lookDir)
            val key = freshKey ?: hoveredKey

            if (InteractionRouter.isDebug(owner.uuid)) {
                val intCount = activeEntities.values.count { it.cell.interactive }
                logger.info("[debug] overlay handleClick: fresh=$freshKey cached=$hoveredKey chosen=$key ent=${activeEntities.size} int=$intCount right=$isRightClick")
            }

            if (key == null) {
                logger.info("handleClick: no cell found (entities=${activeEntities.size})")
                return
            }
            onCellClick?.invoke(key, isRightClick)
        } catch (e: Exception) {
            logger.warning("handleClick error: ${e.message}")
        }
    }

    /** Returns debug info for visual debugging. Call from server tick. */
    fun getDebugInfo(): OverlayDebugInfo? {
        if (isDestroyed) return null
        return try {
            val eyePos = owner.eyePosition()
            val lookDir = owner.lookDirection()
            val interactiveCount = activeEntities.values.count { it.cell.interactive }

            // Compute ray-plane intersection at the first interactive cell's Y level
            var hitPos: Vec3d? = null
            var hitT = 0.0
            val sampleCell = activeEntities.values.firstOrNull { it.cell.interactive }
            if (sampleCell != null && abs(lookDir.y) >= 0.001) {
                val cellY = sampleCell.cell.worldY
                val t = (cellY - eyePos.y) / lookDir.y
                if (t in 0.0..20.0) {
                    hitPos = Vec3d(
                        eyePos.x + lookDir.x * t,
                        cellY,
                        eyePos.z + lookDir.z * t
                    )
                    hitT = t
                }
            }

            OverlayDebugInfo(
                eyePos = eyePos,
                lookDir = lookDir,
                hitPos = hitPos,
                hitT = hitT,
                hoveredKey = hoveredKey,
                entityCount = activeEntities.size,
                interactiveCount = interactiveCount
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Returns positions and sizes of all interactive cells for debug rendering. */
    fun getInteractiveCellBounds(): List<Triple<Double, Double, Double>> {
        return activeEntities.values
            .filter { it.cell.interactive }
            .map { Triple(it.cell.worldX, it.cell.worldZ, it.cell.cellSize.toDouble()) }
    }

    private fun tick() {
        tickCount++

        // Update hover every tick for responsiveness
        updateHover()

        // Update cell visibility at the configured interval
        if (tickCount % updateIntervalTicks.toInt() == 0) {
            updateCells()
        }
    }

    private fun updateCells() {
        val provider = cellProvider ?: return
        val playerPos = owner.eyePosition()
        val desired = provider(playerPos)

        val viewers = setOf(owner.uuid)

        // Remove cells no longer in desired set
        val toRemove = activeEntities.keys - desired.keys
        for (key in toRemove) {
            val oe = activeEntities.remove(key) ?: continue
            platform.packetSender.destroyEntities(listOf(oe.entity.entityId), viewers)
        }

        // Add new cells and update changed ones
        for ((key, cell) in desired) {
            val existing = activeEntities[key]
            if (existing == null) {
                // Spawn new entity with scale-up animation
                spawnCell(key, cell, viewers)
            } else if (existing.cell.material != cell.material) {
                // Material changed — update block state
                existing.cell = cell
                val isHovered = key == hoveredKey && cell.hoverMaterial != null
                existing.entity.blockState = if (isHovered) cell.hoverMaterial!! else cell.material
                platform.packetSender.updateMetadata(existing.entity, viewers)
            }
        }
    }

    private fun spawnCell(key: String, cell: OverlayCell, viewers: Collection<UUID>) {
        val entity = VirtualBlockDisplay()
        entity.position = Vec3d(cell.worldX, cell.worldY, cell.worldZ)
        entity.blockState = cell.material
        entity.brightness = Brightness(15, 15)

        // Start small for scale-up animation
        entity.transformation = Mat4f.scaling(0.1f, 0.02f, 0.1f)
        entity.interpolationDuration = 0
        entity.startInterpolation = 0

        platform.packetSender.spawnEntity(entity, viewers)
        platform.packetSender.updateMetadata(entity, viewers)

        // Immediately set target scale with interpolation for smooth animation
        entity.transformation = Mat4f.scaling(cell.cellSize, 0.02f, cell.cellSize)
        entity.interpolationDuration = 3
        entity.startInterpolation = 0
        platform.packetSender.updateMetadata(entity, viewers)

        activeEntities[key] = OverlayEntity(entity, cell)
    }

    private fun updateHover() {
        val eyePos = owner.eyePosition()
        val lookDir = owner.lookDirection()

        // Find the closest cell the player is looking at via Y-plane raycast
        val newHoveredKey = findHoveredCell(eyePos, lookDir)
        val viewers = setOf(owner.uuid)

        if (newHoveredKey != hoveredKey) {
            // Unhover old
            val oldKey = hoveredKey
            if (oldKey != null) {
                val oe = activeEntities[oldKey]
                if (oe != null) {
                    oe.entity.blockState = oe.cell.material
                    oe.entity.interpolationDuration = 2
                    oe.entity.startInterpolation = 0
                    platform.packetSender.updateMetadata(oe.entity, viewers)
                }
            }

            // Hover new
            hoveredKey = newHoveredKey
            if (newHoveredKey != null) {
                val oe = activeEntities[newHoveredKey]
                if (oe != null && oe.cell.hoverMaterial != null && oe.cell.interactive) {
                    oe.entity.blockState = oe.cell.hoverMaterial!!
                    oe.entity.interpolationDuration = 2
                    oe.entity.startInterpolation = 0
                    platform.packetSender.updateMetadata(oe.entity, viewers)
                }
            }
        }
    }

    private data class CellHit(val key: String, val distance: Double)

    private fun findClosestCell(eyePos: Vec3d, lookDir: Vec3d): CellHit? {
        if (activeEntities.isEmpty()) return null

        var closestKey: String? = null
        var closestDist = Double.MAX_VALUE

        for ((key, oe) in activeEntities) {
            if (!oe.cell.interactive) continue

            val cellY = oe.cell.worldY
            if (abs(lookDir.y) < 0.001) continue
            val t = (cellY - eyePos.y) / lookDir.y
            if (t < 0 || t > 20.0) continue

            val hitX = eyePos.x + lookDir.x * t
            val hitZ = eyePos.z + lookDir.z * t

            val cellMinX = oe.cell.worldX
            val cellMinZ = oe.cell.worldZ
            val cellMaxX = cellMinX + oe.cell.cellSize
            val cellMaxZ = cellMinZ + oe.cell.cellSize

            if (hitX in cellMinX..cellMaxX && hitZ in cellMinZ..cellMaxZ) {
                if (t < closestDist) {
                    closestDist = t
                    closestKey = key
                }
            }
        }

        return if (closestKey != null) CellHit(closestKey, closestDist) else null
    }

    private fun findHoveredCell(eyePos: Vec3d, lookDir: Vec3d): String? =
        findClosestCell(eyePos, lookDir)?.key

    /** Returns the ray distance to the closest hovered cell, or null if not looking at any. */
    fun hitDistance(): Double? {
        return try {
            findClosestCell(owner.eyePosition(), owner.lookDirection())?.distance
        } catch (e: Exception) {
            null
        }
    }
}
