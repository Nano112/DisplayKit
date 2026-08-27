package io.schemat.displaykit.velocity.state

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import io.schemat.displaykit.render.BlockStateRef
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Resolves a BlockStateRef string like "minecraft:oak_stairs[facing=north]"
 * into the global block state id the BLOCK_STATE metadata entry carries.
 *
 * PacketEvents owns the palette, which is what makes this possible on a proxy
 * with no registry of its own. Unknown states fall back to stone with a
 * single warning per id, mirroring the Fabric resolver.
 */
class VelocityBlockStateResolver(private val logger: Logger) {

    private val cache = ConcurrentHashMap<String, Int>()
    private val warned = ConcurrentHashMap.newKeySet<String>()

    fun resolve(ref: BlockStateRef): Int = cache.computeIfAbsent(ref.id) { id ->
        try {
            WrappedBlockState.getByString(id).globalId
        } catch (_: Exception) {
            if (warned.add(id)) {
                logger.warning("Unknown block state '$id', falling back to stone")
            }
            WrappedBlockState.getByString(STONE).globalId
        }
    }

    private companion object {
        const val STONE = "minecraft:stone"
    }
}
