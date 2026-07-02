package io.schemat.displaykit.fabric.state

import io.schemat.displaykit.render.BlockStateRef
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ConcurrentHashMap

object BlockStateResolver {

    private val cache = ConcurrentHashMap<String, BlockState>()
    private var initialized = false

    fun init(server: MinecraftServer) {
        cache.clear()
        initialized = true
    }

    fun resolve(ref: BlockStateRef): BlockState {
        return cache.getOrPut(ref.id) {
            parseBlockState(ref.id)
        }
    }

    /**
     * Parse a block state string like "minecraft:stone" or "minecraft:oak_stairs[facing=north,half=bottom]"
     */
    private fun parseBlockState(id: String): BlockState {
        // Check for properties in brackets
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
        val identifier = Identifier.parse(blockId)
        val block = BuiltInRegistries.BLOCK.getValue(identifier)

        if (block == Blocks.AIR && blockId != "minecraft:air") {
            return Blocks.STONE.defaultBlockState()
        }

        var state = block.defaultBlockState()

        // Apply properties if present
        if (!propertiesStr.isNullOrEmpty()) {
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
}
