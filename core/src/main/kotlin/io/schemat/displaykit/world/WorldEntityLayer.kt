package io.schemat.displaykit.world

import io.schemat.displaykit.platform.PacketSender
import io.schemat.displaykit.render.VirtualEntity
import java.util.UUID
import java.util.function.Function

/** Java-friendly in-place update callback for [WorldEntityLayer]. */
fun interface WorldEntityUpdater<S> {
    fun update(entity: VirtualEntity, previous: S, next: S): WorldEntityUpdate
}

/** Describes which packets an in-place entity update requires. */
data class WorldEntityUpdate(
    val metadata: Boolean = false,
    val transform: Boolean = false,
    val position: Boolean = false,
    val recreate: Boolean = false,
) {
    init {
        require(!recreate || !(metadata || transform || position)) {
            "A recreated entity must not also request in-place updates"
        }
    }

    companion object {
        val NONE = WorldEntityUpdate()
        val METADATA = WorldEntityUpdate(metadata = true)
        val TRANSFORM = WorldEntityUpdate(transform = true)
        val POSITION = WorldEntityUpdate(position = true)
        val RECREATE = WorldEntityUpdate(recreate = true)
    }
}

/** Cumulative churn plus current cardinality for performance assertions and diagnostics. */
data class WorldEntityLayerMetrics(
    val activeEntities: Int = 0,
    val activeViewers: Int = 0,
    val entityViewerSpawns: Long = 0,
    val entityViewerDestroys: Long = 0,
    val metadataUpdates: Long = 0,
    val transformUpdates: Long = 0,
    val positionUpdates: Long = 0,
)

/**
 * A retained, keyed collection of packet-only world entities.
 *
 * Consumers describe state and how to apply an in-place change. The layer
 * owns entity identity, viewer diffs, spawn/despawn lifecycle, and batching.
 * Equal state is a true no-op, making frequent reconciliation inexpensive.
 */
class WorldEntityLayer<K, S>(
    private val packets: PacketSender,
    private val create: (S) -> VirtualEntity,
    private val update: (entity: VirtualEntity, previous: S, next: S) -> WorldEntityUpdate =
        { _, _, _ -> WorldEntityUpdate.RECREATE },
    private val viewerPolicy: WorldViewerPolicy? = null,
    private val onMetrics: (WorldEntityLayerMetrics) -> Unit = {},
) : AutoCloseable {
    private data class Entry<S>(val entity: VirtualEntity, var state: S)

    private val entries = linkedMapOf<K, Entry<S>>()
    private val viewers = linkedSetOf<UUID>()
    private var closed = false
    private var spawnCount = 0L
    private var destroyCount = 0L
    private var metadataCount = 0L
    private var transformCount = 0L
    private var positionCount = 0L

    val size: Int get() = entries.size
    val keys: Set<K> get() = entries.keys.toSet()
    val viewerIds: Set<UUID> get() = viewers.toSet()
    val metrics: WorldEntityLayerMetrics get() = WorldEntityLayerMetrics(
        activeEntities = entries.size,
        activeViewers = viewers.size,
        entityViewerSpawns = spawnCount,
        entityViewerDestroys = destroyCount,
        metadataUpdates = metadataCount,
        transformUpdates = transformCount,
        positionUpdates = positionCount,
    )

    /** Reconcile entities by key, retaining identity for unchanged entries. */
    fun reconcile(desired: Map<K, S>) {
        checkOpen()
        val removed = entries.keys - desired.keys
        for (key in removed) {
            val entry = entries.remove(key) ?: continue
            if (viewers.isNotEmpty()) destroy(listOf(entry.entity.entityId), viewers)
        }

        val metadata = mutableListOf<VirtualEntity>()
        val transforms = mutableListOf<VirtualEntity>()
        for ((key, next) in desired) {
            val existing = entries[key]
            if (existing == null) {
                val entity = create(next)
                entries[key] = Entry(entity, next)
                if (viewers.isNotEmpty()) spawn(entity, viewers)
                continue
            }
            if (existing.state == next) continue

            val change = update(existing.entity, existing.state, next)
            if (change.recreate) {
                if (viewers.isNotEmpty()) {
                    destroy(listOf(existing.entity.entityId), viewers)
                }
                val replacement = create(next)
                entries[key] = Entry(replacement, next)
                if (viewers.isNotEmpty()) spawn(replacement, viewers)
                continue
            }

            existing.state = next
            if (viewers.isEmpty()) continue
            if (change.position) {
                packets.teleportEntity(existing.entity, viewers)
                positionCount += viewers.size
            }
            if (change.metadata) metadata += existing.entity
            if (change.transform) transforms += existing.entity
        }
        if (metadata.isNotEmpty() && viewers.isNotEmpty()) {
            packets.updateMetadataBatch(metadata, viewers)
            metadataCount += metadata.size.toLong() * viewers.size
        }
        if (transforms.isNotEmpty() && viewers.isNotEmpty()) {
            packets.updateTransformBatch(transforms, viewers)
            transformCount += transforms.size.toLong() * viewers.size
        }
        publishMetrics()
    }

    /** Reconcile viewers without disturbing any retained entity identities. */
    fun setViewers(desired: Collection<UUID>) {
        checkOpen()
        val next = desired.toSet()
        val removed = viewers - next
        val added = next - viewers
        val ids = entries.values.map { it.entity.entityId }
        if (removed.isNotEmpty() && ids.isNotEmpty()) destroy(ids, removed)
        if (added.isNotEmpty()) {
            entries.values.forEach { spawn(it.entity, added) }
        }
        viewers.clear()
        viewers.addAll(next)
        publishMetrics()
    }

    /** Apply the configured audience policy without touching entity state. */
    fun reconcileViewers(candidates: Collection<WorldViewer>) {
        val policy = viewerPolicy
            ?: error("This WorldEntityLayer has no viewer policy; call setViewers or configure one")
        setViewers(policy.select(candidates))
    }

    /** Atomically describe both the layer contents and its audience. */
    fun reconcile(desired: Map<K, S>, viewers: Collection<UUID>) {
        reconcile(desired)
        setViewers(viewers)
    }

    fun clear() {
        checkOpen()
        if (entries.isNotEmpty() && viewers.isNotEmpty()) {
            destroy(entries.values.map { it.entity.entityId }, viewers)
        }
        entries.clear()
        publishMetrics()
    }

    override fun close() {
        if (closed) return
        if (entries.isNotEmpty() && viewers.isNotEmpty()) {
            destroy(entries.values.map { it.entity.entityId }, viewers)
        }
        entries.clear()
        viewers.clear()
        closed = true
        publishMetrics()
    }

    private fun spawn(entity: VirtualEntity, audience: Collection<UUID>) {
        packets.spawnEntity(entity, audience)
        spawnCount += audience.size
    }

    private fun destroy(entityIds: Collection<Int>, audience: Collection<UUID>) {
        packets.destroyEntities(entityIds, audience)
        destroyCount += entityIds.size.toLong() * audience.size
    }

    private fun publishMetrics() = onMetrics(metrics)

    private fun checkOpen() = check(!closed) { "WorldEntityLayer is closed" }

    companion object {
        /** Java-friendly layer whose changed values recreate their entity. */
        @JvmStatic
        fun <K, S> recreating(
            packets: PacketSender,
            create: Function<S, VirtualEntity>,
        ): WorldEntityLayer<K, S> = WorldEntityLayer(packets, create::apply)

        /** Java-friendly retained layer with an explicit in-place updater. */
        @JvmStatic
        fun <K, S> retaining(
            packets: PacketSender,
            create: Function<S, VirtualEntity>,
            update: WorldEntityUpdater<S>,
        ): WorldEntityLayer<K, S> = WorldEntityLayer(
            packets = packets,
            create = create::apply,
            update = update::update,
        )
    }
}
