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
            val identifier = Identifier.parse(ref.id)
            val block = BuiltInRegistries.BLOCK.getValue(identifier)
            if (block == Blocks.AIR && ref.id != "minecraft:air") {
                Blocks.STONE.defaultBlockState()
            } else {
                block.defaultBlockState()
            }
        }
    }
}
