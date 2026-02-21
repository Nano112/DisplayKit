package io.schemat.displaykit.render

import io.schemat.displaykit.platform.PlatformProvider
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

class ViewerSet(private val platform: PlatformProvider) {
    enum class Mode { EXPLICIT, ALL }

    var mode: Mode = Mode.EXPLICIT
        private set

    private val explicitViewers = CopyOnWriteArraySet<UUID>()

    fun addViewer(uuid: UUID) {
        mode = Mode.EXPLICIT
        explicitViewers.add(uuid)
    }

    fun removeViewer(uuid: UUID) {
        explicitViewers.remove(uuid)
    }

    fun setAllViewers() {
        mode = Mode.ALL
    }

    fun getViewerUUIDs(): Collection<UUID> = when (mode) {
        Mode.ALL -> platform.getOnlinePlayers().map { it.uuid }
        Mode.EXPLICIT -> explicitViewers.toSet()
    }

    fun contains(uuid: UUID): Boolean = when (mode) {
        Mode.ALL -> true
        Mode.EXPLICIT -> explicitViewers.contains(uuid)
    }

    fun clear() {
        explicitViewers.clear()
    }
}
