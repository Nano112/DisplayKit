package io.schemat.displaykit.velocity.state

import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import io.schemat.displaykit.render.BlockStateRef
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Resolves a BlockStateRef string like "minecraft:oak_stairs[facing=north]"
 * into the global block state id the BLOCK_STATE metadata entry carries.
 *
 * The id is palette-specific, and the palette is the VIEWER's: PacketEvents
 * writes the raw varint with no per-version remap, so resolving against the
 * proxy's own (highest) version would send a 26.x id that decodes to a
 * different block on a 1.21.9 client. The cache is therefore keyed by
 * version and id together. Unknown states fall back to stone with a single
 * warning per id, mirroring the Fabric resolver.
 */
class VelocityBlockStateResolver(private val logger: Logger) {

    private val cache = ConcurrentHashMap<String, Int>()
    private val warned = ConcurrentHashMap.newKeySet<String>()

    fun resolve(ref: BlockStateRef, version: ClientVersion): Int =
        cache.computeIfAbsent("${version.name}:${ref.id}") {
            try {
                WrappedBlockState.getByString(version, ref.id).globalId
            } catch (_: Exception) {
                if (warned.add(ref.id)) {
                    logger.warning("Unknown block state '${ref.id}', falling back to stone")
                }
                WrappedBlockState.getByString(version, STONE).globalId
            }
        }

    private companion object {
        const val STONE = "minecraft:stone"
    }
}
