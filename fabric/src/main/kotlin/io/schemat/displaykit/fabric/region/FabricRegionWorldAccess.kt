package io.schemat.displaykit.fabric.region

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3i
import io.schemat.displaykit.region.BoundingBox
import io.schemat.displaykit.region.CapturedBlock
import io.schemat.displaykit.region.RegionCapture
import io.schemat.displaykit.render.BlockStateRef
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Fabric implementation for capturing and placing block regions.
 */
class FabricRegionWorldAccess(private val level: ServerLevel) {

    /**
     * Capture all blocks in the specified region.
     *
     * @param pos1 First corner
     * @param pos2 Second corner
     * @return Captured region with all non-air blocks
     */
    fun capture(pos1: BlockPos, pos2: BlockPos): RegionCapture {
        val minX = minOf(pos1.x, pos2.x)
        val minY = minOf(pos1.y, pos2.y)
        val minZ = minOf(pos1.z, pos2.z)
        val maxX = maxOf(pos1.x, pos2.x)
        val maxY = maxOf(pos1.y, pos2.y)
        val maxZ = maxOf(pos1.z, pos2.z)

        val bounds = BoundingBox(
            min = Vec3i(0, 0, 0),
            max = Vec3i(maxX - minX, maxY - minY, maxZ - minZ)
        )
        val origin = Vec3d(minX.toDouble(), minY.toDouble(), minZ.toDouble())
        val blocks = mutableMapOf<Vec3i, CapturedBlock>()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val worldPos = BlockPos(x, y, z)
                    val state = level.getBlockState(worldPos)

                    // Skip air blocks
                    if (state.isAir) continue

                    val relativePos = Vec3i(x - minX, y - minY, z - minZ)
                    val blockRef = blockStateToRef(state)
                    val nbt = captureBlockEntityNbt(worldPos)

                    blocks[relativePos] = CapturedBlock(blockRef, nbt)
                }
            }
        }

        return RegionCapture(blocks, bounds, origin)
    }

    /**
     * Clear all blocks in the specified region (replace with air).
     */
    fun clear(pos1: BlockPos, pos2: BlockPos) {
        val minX = minOf(pos1.x, pos2.x)
        val minY = minOf(pos1.y, pos2.y)
        val minZ = minOf(pos1.z, pos2.z)
        val maxX = maxOf(pos1.x, pos2.x)
        val maxY = maxOf(pos1.y, pos2.y)
        val maxZ = maxOf(pos1.z, pos2.z)

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val worldPos = BlockPos(x, y, z)
                    level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), 3)
                }
            }
        }
    }

    /**
     * Clear the bounding box of a capture at the given origin position (replace with air).
     */
    fun clearAt(capture: RegionCapture, origin: Vec3d) {
        val bounds = capture.bounds
        val ox = origin.x.toInt()
        val oy = origin.y.toInt()
        val oz = origin.z.toInt()
        for (x in 0 until bounds.width) {
            for (y in 0 until bounds.height) {
                for (z in 0 until bounds.depth) {
                    level.setBlock(BlockPos(ox + x, oy + y, oz + z), Blocks.AIR.defaultBlockState(), 3)
                }
            }
        }
    }

    /**
     * Place captured blocks at a new location.
     *
     * @param capture The captured region
     * @param destination World position for the region origin (min corner)
     */
    fun place(capture: RegionCapture, destination: Vec3d) {
        val destX = destination.x.toInt()
        val destY = destination.y.toInt()
        val destZ = destination.z.toInt()

        capture.forEach { relativePos, capturedBlock ->
            val worldPos = BlockPos(
                destX + relativePos.x,
                destY + relativePos.y,
                destZ + relativePos.z
            )

            val blockState = refToBlockState(capturedBlock.state)
            level.setBlock(worldPos, blockState, 3)

            // Restore block entity NBT if present
            val nbtData = capturedBlock.nbt
            if (nbtData != null) {
                restoreBlockEntityNbt(worldPos, nbtData)
            }
        }
    }

    /**
     * Convert a Minecraft BlockState to a BlockStateRef.
     */
    private fun blockStateToRef(state: BlockState): BlockStateRef {
        val blockId = BuiltInRegistries.BLOCK.getKey(state.block)?.toString() ?: "minecraft:stone"

        // Include block state properties if any
        val properties = state.values
        if (properties.isEmpty()) {
            return BlockStateRef(blockId)
        }

        // Build property string like "facing=north,half=bottom"
        val propsStr = properties.entries.joinToString(",") { (prop, value) ->
            "${prop.name}=${state.getValue(prop)}"
        }
        return BlockStateRef("$blockId[$propsStr]")
    }

    /**
     * Convert a BlockStateRef back to a Minecraft BlockState.
     */
    private fun refToBlockState(ref: BlockStateRef): BlockState {
        val id = ref.id

        // Parse block ID and properties
        val bracketIndex = id.indexOf('[')
        val blockId: String
        val propertiesStr: String?

        if (bracketIndex != -1) {
            blockId = id.substring(0, bracketIndex)
            propertiesStr = id.substring(bracketIndex + 1, id.length - 1)
        } else {
            blockId = id
            propertiesStr = null
        }

        // Get the block
        val identifier = net.minecraft.resources.Identifier.parse(blockId)
        val block = BuiltInRegistries.BLOCK.getValue(identifier)
        var state = block.defaultBlockState()

        // Apply properties if present
        if (propertiesStr != null && propertiesStr.isNotEmpty()) {
            val stateDefinition = block.stateDefinition
            for (propEntry in propertiesStr.split(",")) {
                val parts = propEntry.split("=")
                if (parts.size == 2) {
                    val propName = parts[0]
                    val propValue = parts[1]

                    val property = stateDefinition.getProperty(propName)
                    if (property != null) {
                        state = setPropertyValue(state, property, propValue)
                    }
                }
            }
        }

        return state
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Comparable<T>> setPropertyValue(
        state: BlockState,
        property: net.minecraft.world.level.block.state.properties.Property<T>,
        value: String
    ): BlockState {
        val parsedValue = property.getValue(value)
        return if (parsedValue.isPresent) {
            state.setValue(property, parsedValue.get())
        } else {
            state
        }
    }

    /**
     * Capture NBT data from a block entity.
     */
    private fun captureBlockEntityNbt(pos: BlockPos): ByteArray? {
        val blockEntity = level.getBlockEntity(pos) ?: return null

        return try {
            val nbt = blockEntity.saveWithFullMetadata(level.registryAccess())
            val outputStream = ByteArrayOutputStream()
            NbtIo.writeCompressed(nbt, DataOutputStream(outputStream))
            outputStream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Restore NBT data to a block entity.
     * Note: Block entity restoration is simplified for now - complex block entities
     * may not restore all their data.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun restoreBlockEntityNbt(pos: BlockPos, nbtData: ByteArray) {
        // TODO: Implement proper NBT restoration for 1.21.4+ API
        // The current Minecraft version uses ValueInput instead of CompoundTag
        // For now, block entities will use their default state after placement
    }

    companion object {
        /**
         * Create a world access for the given level.
         */
        fun of(level: ServerLevel) = FabricRegionWorldAccess(level)
    }
}
