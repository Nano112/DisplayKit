package io.schemat.displaykit.surface

import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.render.VirtualBlockDisplay
import io.schemat.displaykit.render.VirtualEntity
import io.schemat.displaykit.render.VirtualTextDisplay

/**
 * Owns a surface's entity lifecycle for one viewer.
 *
 * A repaint re-emits one text component and sends one metadata packet — no
 * entity churn — which is what makes hover affordable.
 */
class SurfaceHost(
    private val platform: PlatformProvider,
    private val owner: PlayerRef,
    val surface: Surface
) {
    private var entity: VirtualTextDisplay? = null
    private var backing: VirtualBlockDisplay? = null
    private val viewers get() = setOf(owner.uuid)

    var hovered: String? = null
        private set

    fun open() {
        if (entity != null) return
        // The backing panel spawns first so it is already behind the glyphs
        // on the very first frame the viewer sees.
        surface.toBackingEntity()?.let { b ->
            backing = b
            platform.packetSender.spawnEntity(b, viewers)
            platform.packetSender.updateMetadata(b, viewers)
        }
        val e = surface.toEntity()
        entity = e
        platform.packetSender.spawnEntity(e, viewers)
        platform.packetSender.updateMetadata(e, viewers)
    }

    /** Push the current canvas to the client. Cheap: one metadata packet. */
    fun repaint() {
        val e = entity ?: return
        val fresh = surface.toEntity()
        e.text = fresh.text
        e.transformation = fresh.transformation
        // Not surface.position: a text display is placed by its block
        // centre, so the entity origin is offset from the canvas top-left
        // (see Surface.entityOrigin).
        e.position = fresh.position
        platform.packetSender.updateMetadata(e, viewers)
        backing?.let { b ->
            surface.toBackingEntity()?.let { f ->
                b.position = f.position
                b.transformation = f.transformation
                platform.packetSender.updateMetadata(b, viewers)
            }
        }
    }

    /** Returns true when the click landed on a region and was consumed. */
    fun handleClick(): Boolean {
        val hit = SurfacePicking.hit(surface, owner.eyePosition(), owner.lookDirection()) ?: return false
        hit.onClick()
        return true
    }

    /** Returns true when the hovered region changed, so the caller can repaint. */
    fun tickHover(): Boolean {
        val id = SurfacePicking.hit(surface, owner.eyePosition(), owner.lookDirection())?.id
        if (id == hovered) return false
        hovered = id
        return true
    }

    fun close() {
        val ids = listOfNotNull(entity?.entityId, backing?.entityId)
        if (ids.isEmpty()) return
        platform.packetSender.destroyEntities(ids, viewers)
        entity = null
        backing = null
    }

    fun entities(): List<VirtualEntity> = listOfNotNull(backing, entity)
}
