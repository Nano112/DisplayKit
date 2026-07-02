package io.schemat.displaykit.region

import io.schemat.displaykit.render.BlockStateRef

/**
 * A single captured block with its state and optional NBT data.
 *
 * @param state The block state reference (e.g., "minecraft:chest[facing=north]")
 * @param nbt Serialized NBT data for block entities (null for simple blocks)
 */
data class CapturedBlock(
    val state: BlockStateRef,
    val nbt: ByteArray? = null
) {
    /**
     * Whether this block has NBT data (is a block entity).
     */
    val hasNbt: Boolean get() = nbt != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedBlock) return false
        if (state != other.state) return false
        if (nbt != null) {
            if (other.nbt == null) return false
            if (!nbt.contentEquals(other.nbt)) return false
        } else if (other.nbt != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = state.hashCode()
        result = 31 * result + (nbt?.contentHashCode() ?: 0)
        return result
    }
}
