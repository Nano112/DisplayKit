package io.schemat.displaykit.fabric.region

import com.mojang.math.Transformation
import io.schemat.displaykit.fabric.state.BlockStateResolver
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3f
import io.schemat.displaykit.math.Vec3i
import io.schemat.displaykit.region.CapturedBlock
import io.schemat.displaykit.region.RegionCapture
import io.schemat.displaykit.region.RegionDisplayManager
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntitySpawnReason
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Display manager using real server-side Display.BlockDisplay entities.
 * Visible to ALL players automatically via Minecraft's entity tracker.
 * No viewer management needed.
 *
 * Interior blocks (fully surrounded on all 6 faces) are culled at spawn.
 */
class FabricRegionDisplayManager(
    override val capture: RegionCapture,
    private val level: ServerLevel
) : RegionDisplayManager {

    private val displays = mutableMapOf<Vec3i, Display.BlockDisplay>()

    override var isSpawned: Boolean = false
        private set

    override val displayCount: Int get() = displays.size

    override fun spawn() {
        if (isSpawned) return
        if (capture.isEmpty) { isSpawned = true; return }

        val blocks = capture.blocks
        capture.forEach { relPos, capturedBlock ->
            if (isInterior(relPos, blocks)) return@forEach

            val display = EntityType.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND)
                ?: return@forEach

            display.setPos(capture.origin.x, capture.origin.y, capture.origin.z)
            display.setBlockState(BlockStateResolver.resolve(capturedBlock.state))

            // Set local transform (offset from origin)
            val tx = relPos.x.toFloat()
            val ty = relPos.y.toFloat()
            val tz = relPos.z.toFloat()
            display.setTransformation(Transformation(
                Vector3f(tx, ty, tz),
                Quaternionf(),
                Vector3f(1f, 1f, 1f),
                Quaternionf()
            ))

            // Interpolation, brightness, view range via entity data
            // Set start_interpolation to -1 so that setting it to 0 in animateTransform()
            // triggers a dirty flag change, causing the client to actually receive the signal
            display.entityData.set(DATA_INTERPOLATION_DELAY, -1)
            display.entityData.set(DATA_INTERPOLATION_DURATION, 3)
            display.entityData.set(DATA_BRIGHTNESS, packBrightness(15, 15))
            display.entityData.set(DATA_VIEW_RANGE, 1f)

            level.addFreshEntity(display)
            displays[relPos] = display
        }

        isSpawned = true
    }

    override fun destroy() {
        if (!isSpawned) return
        for (display in displays.values) {
            display.discard()
        }
        displays.clear()
        isSpawned = false
    }

    private var interpolationToggle = false

    override fun animateTransform(
        targetPosition: Vec3d,
        targetRotation: Quaternionf,
        targetScale: Vec3f,
        durationTicks: Int
    ) {
        if (!isSpawned || displays.isEmpty()) return

        val dx = (targetPosition.x - capture.origin.x).toFloat()
        val dy = (targetPosition.y - capture.origin.y).toFloat()
        val dz = (targetPosition.z - capture.origin.z).toFloat()

        // Toggle between -1 and 0 to ensure the dirty flag is always triggered,
        // so the client receives each new interpolation start signal
        interpolationToggle = !interpolationToggle
        val delayValue = if (interpolationToggle) 0 else -1

        for ((relPos, display) in displays) {
            val scaledX = relPos.x.toFloat() * targetScale.x
            val scaledY = relPos.y.toFloat() * targetScale.y
            val scaledZ = relPos.z.toFloat() * targetScale.z

            val rotated = Vector3f(scaledX, scaledY, scaledZ)
            targetRotation.transform(rotated)

            display.setTransformation(Transformation(
                Vector3f(rotated.x + dx, rotated.y + dy, rotated.z + dz),
                Quaternionf(),
                Vector3f(1f, 1f, 1f),
                Quaternionf()
            ))
            display.entityData.set(DATA_INTERPOLATION_DURATION, durationTicks)
            display.entityData.set(DATA_INTERPOLATION_DELAY, delayValue)
        }
    }

    override fun setVisibility(visible: Boolean) {
        if (!isSpawned || displays.isEmpty()) return
        val range = if (visible) 1f else 0f
        for (display in displays.values) {
            display.entityData.set(DATA_VIEW_RANGE, range)
        }
    }

    private fun isInterior(pos: Vec3i, blocks: Map<Vec3i, CapturedBlock>): Boolean {
        return blocks.containsKey(Vec3i(pos.x + 1, pos.y, pos.z)) &&
               blocks.containsKey(Vec3i(pos.x - 1, pos.y, pos.z)) &&
               blocks.containsKey(Vec3i(pos.x, pos.y + 1, pos.z)) &&
               blocks.containsKey(Vec3i(pos.x, pos.y - 1, pos.z)) &&
               blocks.containsKey(Vec3i(pos.x, pos.y, pos.z + 1)) &&
               blocks.containsKey(Vec3i(pos.x, pos.y, pos.z - 1))
    }

    companion object {
        // Entity data accessors using known indices from Display class (Mojang 1.21.x)
        private val DATA_INTERPOLATION_DELAY = EntityDataAccessor(8, EntityDataSerializers.INT)
        private val DATA_INTERPOLATION_DURATION = EntityDataAccessor(9, EntityDataSerializers.INT)
        private val DATA_BRIGHTNESS = EntityDataAccessor(16, EntityDataSerializers.INT)
        private val DATA_VIEW_RANGE = EntityDataAccessor(17, EntityDataSerializers.FLOAT)

        private fun packBrightness(block: Int, sky: Int): Int = (block shl 4) or sky
    }
}
