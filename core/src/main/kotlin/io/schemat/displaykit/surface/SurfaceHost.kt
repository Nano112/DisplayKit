package io.schemat.displaykit.surface

import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.render.VirtualBlockDisplay
import io.schemat.displaykit.render.VirtualEntity
import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.surface.layout.SurfaceNode

/**
 * Owns a surface's entity lifecycle for one viewer.
 *
 * A repaint re-emits one text component and sends one metadata packet — no
 * entity churn — which is what makes hover affordable.
 */
private val DEBUG_LAYERS = System.getProperty("displaykit.debug.layers") == "true"

class SurfaceHost(
    private val platform: PlatformProvider,
    private val owner: PlayerRef,
    val surface: Surface
) {
    private var layers: List<VirtualTextDisplay> = emptyList()
    private var backing: VirtualBlockDisplay? = null
    private var pointer: VirtualTextDisplay? = null
    private val viewers get() = setOf(owner.uuid)

    var hovered: String? = null
        private set

    /**
     * Last pixel the pointer occupied, so a grab can keep being fed once the
     * ray leaves the surface. A drag must survive the cursor straying off the
     * panel -- that is the whole reason a grab outlives [SurfaceFocus.pointerLost].
     */
    private var lastPointerPx: Pair<Int, Int>? = null

    fun open() {
        if (layers.isNotEmpty()) return
        // The backing panel spawns first so it is already behind the glyphs
        // on the very first frame the viewer sees.
        surface.toBackingEntity()?.let { b ->
            backing = b
            platform.packetSender.spawnEntity(b, viewers)
            platform.packetSender.updateMetadata(b, viewers)
        }
        layers = surface.toEntities()
        if (DEBUG_LAYERS) println(surface.describeLayersForDebug(layers))
        for (e in layers) {
            platform.packetSender.spawnEntity(e, viewers)
            platform.packetSender.updateMetadata(e, viewers)
        }
    }

    /** Push the current canvas to the client. Cheap: one metadata packet. */
    fun repaint() {
        if (layers.isEmpty()) return
        val fresh = surface.toEntities()
        // A repaint that changed the layer count needs new entities, not new
        // metadata -- fall back to a full cycle rather than silently dropping
        // or orphaning one.
        if (fresh.size != layers.size) {
            close()
            open()
            return
        }
        for ((e, f) in layers.zip(fresh)) {
            e.text = f.text
            e.transformation = f.transformation
            // Not surface.position: a text display is placed by its block
            // centre, so the entity origin is offset from the canvas top-left
            // (see Surface.entityOrigin).
            e.position = f.position
            platform.packetSender.updateMetadata(e, viewers)
        }
        backing?.let { b ->
            surface.toBackingEntity()?.let { f ->
                b.position = f.position
                b.transformation = f.transformation
                platform.packetSender.updateMetadata(b, viewers)
            }
        }
    }

    /**
     * Returns true when the click landed on a region and was consumed.
     *
     * Kept for surfaces built with [Surface.region] and no layout tree at
     * all -- [handleClick] falls back to this when the tree doesn't consume
     * the click (or has no root).
     */
    private fun handleClickLegacy(): Boolean {
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

    /**
     * One tick of pointer work for this surface: raycast, move the cursor,
     * fire enter/exit and move events, and feed an active grab.
     *
     * Returns true if the surface was repainted, so the caller can avoid
     * repainting twice. Repaint happens ONLY on a hover change -- every tick
     * would be ~140 metadata packets a second per viewer.
     */
    fun tick(): Boolean {
        val point = SurfacePicking.localPixel(surface, owner.eyePosition(), owner.lookDirection())
        val player = owner.uuid

        if (point == null) {
            hidePointer()
            // A grab must keep tracking even once the ray leaves the surface
            // entirely -- feed it the last known pixel rather than dropping
            // it or teleporting it to (0, 0).
            lastPointerPx?.let { (lx, ly) -> SurfaceFocus.grabbed(player)?.onGrabMove?.invoke(lx, ly) }
            val previous = SurfaceFocus.hovered(player)
            val changed = SurfaceFocus.pointerLost(player)
            if (changed) {
                val (lx, ly) = lastPointerPx ?: (0 to 0)
                previous?.let { surface.dispatch(SurfaceEvent.PointerExit(lx, ly), target = it) }
                repaint()
            }
            return changed
        }

        val (px, py) = point
        lastPointerPx = point
        showPointer(px, py)

        // A grab keeps receiving movement even over other nodes; that is the
        // point of grabbing.
        SurfaceFocus.grabbed(player)?.let { it.onGrabMove?.invoke(px, py) }

        val node = surface.nodeAt(px, py)
        val previous = SurfaceFocus.hovered(player)
        val changed = SurfaceFocus.pointerAt(player, node)
        if (changed) {
            previous?.let { surface.dispatch(SurfaceEvent.PointerExit(px, py), target = it) }
            node?.let { surface.dispatch(SurfaceEvent.PointerEnter(px, py), target = it) }
            repaint()
        }
        surface.dispatch(SurfaceEvent.PointerMove(px, py))
        return changed
    }

    /** Route a click. Returns true when the surface consumed it. */
    fun handleClick(button: PointerButton): Boolean {
        val point = SurfacePicking.localPixel(surface, owner.eyePosition(), owner.lookDirection())
            ?: return false
        val (px, py) = point
        val player = owner.uuid

        // Click-to-grab, click-to-release: there is no reliable press-and-hold
        // against a floating entity -- START_DESTROY_BLOCK/STOP_DESTROY_BLOCK
        // only fire against blocks, and swing rate against open-air entities
        // is client- and latency-dependent.
        if (SurfaceFocus.grabbed(player) != null) {
            SurfaceFocus.release(player)
            return true
        }
        val node = surface.nodeAt(px, py)
        if (node != null && node.onGrabMove != null && SurfaceFocus.grab(player, node)) {
            return true
        }

        if (surface.dispatch(SurfaceEvent.Click(px, py, button)) != null) {
            repaint()
            return true
        }
        // Fall back to the pre-tree region list so existing surfaces still work.
        return handleClickLegacy()
    }

    /** Route a scroll notch. Returns true when the surface consumed it. */
    fun handleScroll(delta: Int): Boolean {
        val point = SurfacePicking.localPixel(surface, owner.eyePosition(), owner.lookDirection())
            ?: return false
        val (px, py) = point
        val consumed = surface.dispatch(SurfaceEvent.Scroll(px, py, delta)) != null
        if (consumed) repaint()
        return consumed
    }

    /** Show or move the cursor to canvas ([px], [py]). */
    fun showPointer(px: Int, py: Int) {
        val fresh = surface.pointerEntityAt(px, py) ?: return
        val existing = pointer
        if (existing == null) {
            pointer = fresh
            platform.packetSender.spawnEntity(fresh, viewers)
            platform.packetSender.updateMetadata(fresh, viewers)
        } else {
            existing.position = fresh.position
            existing.text = fresh.text
            // yawDegrees is mutable and repaint() refreshes the layers'
            // transformation when it changes -- without also copying these,
            // a re-faced surface would leave the pointer rotated the old way.
            existing.transformation = fresh.transformation
            existing.lineWidth = fresh.lineWidth
            platform.packetSender.updateMetadata(existing, viewers)
        }
    }

    fun hidePointer() {
        val p = pointer ?: return
        platform.packetSender.destroyEntities(listOf(p.entityId), viewers)
        pointer = null
    }

    fun close() {
        val ids = layers.map { it.entityId } +
            listOfNotNull(backing?.entityId, pointer?.entityId)
        if (ids.isEmpty()) return
        platform.packetSender.destroyEntities(ids, viewers)
        layers = emptyList()
        backing = null
        pointer = null
        lastPointerPx = null
        SurfaceFocus.clear(owner.uuid)
    }

    fun entities(): List<VirtualEntity> = listOfNotNull(backing) + layers + listOfNotNull(pointer)
}
