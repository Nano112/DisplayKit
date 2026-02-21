package io.schemat.displaykit.platform

import io.schemat.displaykit.render.VirtualEntity
import java.util.UUID

interface PacketSender {
    fun spawnEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>)
    fun updateMetadata(entity: VirtualEntity, viewerUUIDs: Collection<UUID>)
    fun teleportEntity(entity: VirtualEntity, viewerUUIDs: Collection<UUID>)
    fun destroyEntities(entityIds: Collection<Int>, viewerUUIDs: Collection<UUID>)
}
