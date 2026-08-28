package io.schemat.displaykit.surface

import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.render.VirtualBlockDisplay
import io.schemat.displaykit.render.VirtualEntity
import io.schemat.displaykit.render.VirtualItemDisplay
import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.surface.layout.SurfaceNode

/**
 * Owns a surface's entity lifecycle for one viewer.
 *
 * Repaints reconcile the freshly composed entities against the live ones and
 * send only changed metadata. Stable frames are packet-free, which keeps
 * hover and pointer tracking from restarting display state.
 */
private val DEBUG_LAYERS = System.getProperty("displaykit.debug.layers") == "true"

class SurfaceHost(
    private val platform: PlatformProvider,
    private val owner: PlayerRef,
    val surface: Surface
) {
    /**
     * Session-owned lifecycle gate, invoked before pointer work each tick.
     * Returning false means the session closed or is otherwise no longer
     * eligible for interaction, so this host skips the remainder of the tick.
     */
    internal var lifecycleTick: (() -> Boolean)? = null

    /**
     * Session-owned teardown hook, invoked at the top of [close].
     *
     * [io.schemat.displaykit.ui.InteractionRouter.cleanupPlayer] closes hosts
     * directly when it drops a player's context, which bypasses the owning
     * session entirely. Without this hook a session would keep reporting
     * itself open after its surface had been destroyed, and the onClosed
     * callback a consumer registered would never run.
     */
    internal var onHostClosed: (() -> Unit)? = null

    private var layers: List<VirtualEntity> = emptyList()
    private var backing: VirtualBlockDisplay? = null
    private var pointer: VirtualTextDisplay? = null
    private val viewers get() = setOf(owner.uuid)

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
        }
        layers = surface.toEntities()
        if (DEBUG_LAYERS) println(surface.describeLayersForDebug(layers))
        for (e in layers) {
            platform.packetSender.spawnEntity(e, viewers)
        }
    }

    /** Push only canvas layers whose visual state actually changed. */
    fun repaint() {
        if (layers.isEmpty()) return
        val fresh = surface.toEntities()
        val unused = layers.toMutableSet()
        val keyed = layers
            .filter { it.reconcileKey != null }
            .groupBy { it.reconcileKey }
        val reconciled = ArrayList<VirtualEntity>(fresh.size)

        for ((index, next) in fresh.withIndex()) {
            val existing = next.reconcileKey?.let { key ->
                keyed[key].orEmpty().firstOrNull { it in unused && it::class == next::class }
            } ?: layers.getOrNull(index)?.takeIf {
                // Compatibility for manually-painted surfaces which predate
                // identity scopes. Index reconciliation remains safe only
                // while the layer count and medium are stable.
                fresh.size == layers.size && it in unused &&
                    it.reconcileKey == null && it::class == next::class
            }

            if (existing == null) {
                platform.packetSender.spawnEntity(next, viewers)
                reconciled += next
            } else {
                unused -= existing
                syncEntity(existing, next)
                reconciled += existing
            }
        }

        if (unused.isNotEmpty()) {
            platform.packetSender.destroyEntities(unused.map { it.entityId }, viewers)
        }
        layers = reconciled
        backing?.let { b ->
            surface.toBackingEntity()?.let { f ->
                val transformed = b.transformation != f.transformation
                if (b.position != f.position) b.position = f.position
                if (transformed) b.transformation = f.transformation
                if (transformed) platform.packetSender.updateMetadata(b, viewers)
            }
        }
    }

    /** Copy one freshly composed primitive onto the live entity with its stable id. */
    private fun syncEntity(existing: VirtualEntity, fresh: VirtualEntity) {
        var metadataChanged = false

        fun changed(value: Boolean, update: () -> Unit) {
            if (value) {
                update()
                metadataChanged = true
            }
        }

        changed(existing.transformation != fresh.transformation) { existing.transformation = fresh.transformation }
        changed(existing.translation != fresh.translation) { existing.translation = fresh.translation }
        changed(existing.scale != fresh.scale) { existing.scale = fresh.scale }
        changed(existing.billboard != fresh.billboard) { existing.billboard = fresh.billboard }
        changed(existing.brightness != fresh.brightness) { existing.brightness = fresh.brightness }
        changed(existing.viewRange != fresh.viewRange) { existing.viewRange = fresh.viewRange }
        changed(existing.glowColorOverride != fresh.glowColorOverride) {
            existing.glowColorOverride = fresh.glowColorOverride
        }
        changed(existing.glowing != fresh.glowing) { existing.glowing = fresh.glowing }
        changed(existing.interpolationDuration != fresh.interpolationDuration) {
            existing.interpolationDuration = fresh.interpolationDuration
        }
        changed(existing.teleportDuration != fresh.teleportDuration) {
            existing.teleportDuration = fresh.teleportDuration
        }
        changed(existing.startInterpolation != fresh.startInterpolation) {
            existing.startInterpolation = fresh.startInterpolation
        }

        when {
            existing is VirtualTextDisplay && fresh is VirtualTextDisplay -> {
                changed(existing.text != fresh.text) { existing.text = fresh.text }
                changed(existing.backgroundColor != fresh.backgroundColor) {
                    existing.backgroundColor = fresh.backgroundColor
                }
                changed(existing.textAlignment != fresh.textAlignment) {
                    existing.textAlignment = fresh.textAlignment
                }
                changed(existing.lineWidth != fresh.lineWidth) { existing.lineWidth = fresh.lineWidth }
                changed(existing.isSeeThrough != fresh.isSeeThrough) {
                    existing.isSeeThrough = fresh.isSeeThrough
                }
                changed(existing.textOpacity != fresh.textOpacity) { existing.textOpacity = fresh.textOpacity }
                changed(existing.hasShadow != fresh.hasShadow) { existing.hasShadow = fresh.hasShadow }
            }
            existing is VirtualBlockDisplay && fresh is VirtualBlockDisplay -> {
                changed(existing.blockState != fresh.blockState) { existing.blockState = fresh.blockState }
            }
            existing is VirtualItemDisplay && fresh is VirtualItemDisplay -> {
                changed(existing.itemId != fresh.itemId) { existing.itemId = fresh.itemId }
                changed(existing.customModelData != fresh.customModelData) {
                    existing.customModelData = fresh.customModelData
                }
                changed(existing.itemColor != fresh.itemColor) { existing.itemColor = fresh.itemColor }
                changed(existing.itemDisplayTransform != fresh.itemDisplayTransform) {
                    existing.itemDisplayTransform = fresh.itemDisplayTransform
                }
            }
        }

        // Not surface.position: a text display is placed by its block centre,
        // so the entity origin is offset from the canvas top-left.
        val moved = existing.position != fresh.position
        if (moved) existing.position = fresh.position
        if (metadataChanged) platform.packetSender.updateMetadata(existing, viewers)
        if (moved && existing.teleportDuration > 0) {
            platform.packetSender.teleportEntity(existing, viewers)
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

    /**
     * Re-render the tree, if this surface has one, and push the result.
     *
     * A hover change -- the pointer moving onto a new node, moving between
     * two nodes, or leaving the surface entirely -- dispatches
     * PointerEnter/PointerExit and then must show the result: [repaint] only
     * re-serialises the canvas as it already stands, so a hover-dependent
     * widget's highlight would never appear when it starts, and never clear
     * when it ends, without re-running [Surface.paintTree] first. Both
     * [tick]'s branches call this so the pairing cannot drift apart -- a
     * surface built with a manual `paint {}` and no tree is left untouched by
     * the `root != null` guard, same as [repaint] itself already assumes
     * nothing about how the canvas got its content.
     */
    private fun repaintTreeAndPush() = SurfaceRepaintGuard.guarded {
        // Guarded because a hover repaint can allocate glyph variants a
        // pre-warm missed -- a hover-dependent widget swaps sprite states --
        // and pushing a glyph the client's pack does not define draws a
        // missing-glyph box with nothing logged anywhere. See
        // SurfaceRepaintGuard.
        if (surface.root != null) surface.paintTree()
        repaint()
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
        if (lifecycleTick?.invoke() == false) return false
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
                repaintTreeAndPush()
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
            repaintTreeAndPush()
        }
        surface.dispatch(SurfaceEvent.PointerMove(px, py))
        return changed
    }

    /** Route a click. Returns true when the surface consumed it. */
    fun handleClick(button: PointerButton): Boolean {
        val player = owner.uuid

        // Click-to-grab, click-to-release: there is no reliable press-and-hold
        // against a floating entity -- START_DESTROY_BLOCK/STOP_DESTROY_BLOCK
        // only fire against blocks, and swing rate against open-air entities
        // is client- and latency-dependent.
        //
        // Checked BEFORE the localPixel null-return below: tick() deliberately
        // keeps feeding an active grab once the ray leaves the surface (see
        // lastPointerPx), so a drag that strays off the panel must still be
        // releasable by a click even while the pointer is off-surface -- a
        // release gated on a live pixel would strand it grabbed until the ray
        // happened to come back.
        if (SurfaceFocus.grabbed(player) != null) {
            SurfaceFocus.release(player)
            return true
        }

        val point = SurfacePicking.localPixel(surface, owner.eyePosition(), owner.lookDirection())
            ?: return false
        val (px, py) = point

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
        } else {
            val moved = existing.position != fresh.position
            val metadataChanged =
                existing.text != fresh.text ||
                    existing.transformation != fresh.transformation ||
                    existing.lineWidth != fresh.lineWidth
            if (moved) existing.position = fresh.position
            if (existing.text != fresh.text) existing.text = fresh.text
            // yawDegrees is mutable and repaint() refreshes the layers'
            // transformation when it changes -- without also copying these,
            // a re-faced surface would leave the pointer rotated the old way.
            if (existing.transformation != fresh.transformation) {
                existing.transformation = fresh.transformation
            }
            if (existing.lineWidth != fresh.lineWidth) existing.lineWidth = fresh.lineWidth
            if (metadataChanged) platform.packetSender.updateMetadata(existing, viewers)
            // Position is NOT metadata. Assigning it and sending only a
            // metadata packet leaves the client rendering the cursor wherever
            // it was spawned, which is why it appeared correctly the moment
            // the ray entered the surface and then never followed the
            // crosshair again. Moving a display entity takes a teleport, as
            // the interaction design specified and this did not do.
            //
            // Both metadata and movement are guarded on actual changes so a
            // still cursor costs nothing: this runs every tick, per viewer.
            if (moved) platform.packetSender.teleportEntity(existing, viewers)
        }
    }

    fun hidePointer() {
        val p = pointer ?: return
        platform.packetSender.destroyEntities(listOf(p.entityId), viewers)
        pointer = null
    }

    /**
     * Tear down this window's entities. Does NOT touch [SurfaceFocus] --
     * focus is per-VIEWER, not per-host ([SurfaceFocus]'s own KDoc), so
     * clearing it here would wipe hover/grab/scrollArmed for every OTHER
     * surface the player has open. [InteractionRouter.unregisterSurface]
     * owns that decision, and only takes it once the player has no surfaces
     * left.
     */
    fun close() {
        // Before the early return below: a host closed before it ever painted
        // still has a session that needs to hear about it
        onHostClosed?.invoke()
        val ids = layers.map { it.entityId } +
            listOfNotNull(backing?.entityId, pointer?.entityId)
        if (ids.isEmpty()) return
        platform.packetSender.destroyEntities(ids, viewers)
        layers = emptyList()
        backing = null
        pointer = null
        lastPointerPx = null
    }

    fun entities(): List<VirtualEntity> = listOfNotNull(backing) + layers + listOfNotNull(pointer)
}
