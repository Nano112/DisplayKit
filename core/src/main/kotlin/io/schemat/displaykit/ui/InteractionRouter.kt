package io.schemat.displaykit.ui

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

object InteractionRouter {
    private val logger = Logger.getLogger("displaykit/router")
    private val activeOverlays = ConcurrentHashMap<UUID, MutableList<WorldOverlay>>()
    private val activeSurfaces = ConcurrentHashMap<UUID, MutableList<io.schemat.displaykit.surface.SurfaceHost>>()
    private val debugPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val lastConsumedLeftClick = ConcurrentHashMap<UUID, Long>()

    /**
     * Players whose retained surfaces should NOT receive clicks — set while a tool
     * flow (Place/Move/Edit) is active so panels hovering over buildings
     * (e.g. the island terminal above the pavilion) can't swallow the clicks
     * meant for the world. Overlays still dispatch normally.
     */
    private val uiSuppressed = ConcurrentHashMap.newKeySet<UUID>()

    fun setUiSuppressed(playerUUID: UUID, suppressed: Boolean) {
        if (suppressed) uiSuppressed.add(playerUUID) else uiSuppressed.remove(playerUUID)
    }
    private const val CONSUMED_WINDOW_MS = 100L

    /** Optional callback for debug click info — set by server module. */
    var onDebugClick: ((playerUUID: UUID, info: String) -> Unit)? = null

    fun setDebug(playerUUID: UUID, enabled: Boolean) {
        if (enabled) debugPlayers.add(playerUUID) else debugPlayers.remove(playerUUID)
    }

    fun isDebug(playerUUID: UUID): Boolean = playerUUID in debugPlayers

    fun registerOverlay(playerUUID: UUID, overlay: WorldOverlay) {
        activeOverlays.getOrPut(playerUUID) { mutableListOf() }.add(overlay)
    }

    fun unregisterOverlay(playerUUID: UUID, overlay: WorldOverlay) {
        val list = activeOverlays[playerUUID] ?: return
        list.remove(overlay)
        if (list.isEmpty()) activeOverlays.remove(playerUUID)
    }

    fun hasActiveUI(playerUUID: UUID): Boolean {
        return activeSurfaces[playerUUID]?.isNotEmpty() == true
    }

    fun getOverlaysForPlayer(playerUUID: UUID): List<WorldOverlay> {
        return activeOverlays[playerUUID]?.toList() ?: emptyList()
    }

    fun registerSurface(playerUUID: UUID, host: io.schemat.displaykit.surface.SurfaceHost) {
        activeSurfaces.computeIfAbsent(playerUUID) { mutableListOf() }.add(host)
    }

    fun unregisterSurface(playerUUID: UUID, host: io.schemat.displaykit.surface.SurfaceHost) {
        activeSurfaces[playerUUID]?.remove(host)
        if (activeSurfaces[playerUUID]?.isEmpty() == true) {
            activeSurfaces.remove(playerUUID)
            // Focus is per-VIEWER, not per-host: only drop it once the player
            // has no surfaces left. Clearing it in SurfaceHost.close() meant a
            // repaint that changed the layer count cancelled an active drag,
            // and closing one window disarmed another.
            io.schemat.displaykit.surface.SurfaceFocus.clear(playerUUID)
        }
    }

    fun getSurfaces(playerUUID: UUID): List<io.schemat.displaykit.surface.SurfaceHost> =
        activeSurfaces[playerUUID]?.toList() ?: emptyList()

    /** Returns true when a surface consumed the click. */
    fun handleSurfaceClick(playerUUID: UUID, isRightClick: Boolean = false): Boolean =
        playerUUID !in uiSuppressed && getSurfaces(playerUUID).any {
            it.handleClick(
                if (isRightClick) io.schemat.displaykit.surface.PointerButton.RIGHT
                else io.schemat.displaykit.surface.PointerButton.LEFT
            )
        }

    fun hasHoveredOverlay(playerUUID: UUID): Boolean {
        val overlays = activeOverlays[playerUUID] ?: return false
        return try {
            overlays.any { !it.isDestroyed() && it.isLookingAtCell() }
        } catch (e: Exception) {
            if (isDebug(playerUUID)) {
                logger.warning("[debug $playerUUID] hasHoveredOverlay exception: ${e.message}")
            }
            false
        }
    }

    /**
     * Check if player is targeting any interactive surface (overlay or UI).
     * Used by mixins to gate block-breaking/entity actions without dispatching clicks.
     */
    fun isTargetingInteractive(playerUUID: UUID): Boolean {
        return hasHoveredOverlay(playerUUID) ||
            (playerUUID !in uiSuppressed && io.schemat.displaykit.surface.SurfaceFocus.hovered(playerUUID) != null)
    }

    /**
     * Central left-click handler. Called from ServerboundSwingPacket mixin.
     * Debounces, then raycasts all UIs and overlays, dispatching to the closest hit. Returns true if consumed.
     */
    fun onLeftClick(playerUUID: UUID): Boolean {
        val consumed = dispatchClick(playerUUID, isRightClick = false)
        if (consumed) {
            lastConsumedLeftClick[playerUUID] = System.currentTimeMillis()
        }
        return consumed
    }

    /**
     * Was a left-click recently consumed? Used by the safety net (PlayerBlockBreakEvents.BEFORE)
     * to catch block breaks that follow a consumed click, even if overlay state changed in between.
     */
    fun wasLeftClickConsumed(playerUUID: UUID): Boolean {
        val last = lastConsumedLeftClick[playerUUID] ?: return false
        return (System.currentTimeMillis() - last) < CONSUMED_WINDOW_MS
    }

    /**
     * Central right-click handler. Called from UseItemOn/UseItem mixins.
     * Debounces, then raycasts all UIs and overlays, dispatching to the closest hit. Returns true if consumed.
     */
    fun onRightClick(playerUUID: UUID): Boolean {
        return dispatchClick(playerUUID, isRightClick = true)
    }

    /** Raycast all UIs and overlays, dispatch to the closest hit along the ray. */
    private fun dispatchClick(playerUUID: UUID, isRightClick: Boolean): Boolean {
        val debug = isDebug(playerUUID)
        val side = if (isRightClick) "R" else "L"

        var closestDist = Double.MAX_VALUE
        var closestAction: (() -> Unit)? = null
        var closestLabel = ""

        // Collect overlay hits
        val overlays = activeOverlays[playerUUID]
        if (overlays != null) {
            for (overlay in overlays.toList()) {
                if (overlay.isDestroyed()) continue
                val dist = overlay.hitDistance() ?: continue
                if (dist < closestDist) {
                    closestDist = dist
                    closestAction = { overlay.handleClick(isRightClick) }
                    closestLabel = "$side:ov"
                }
            }
        }

        val input = InteractionInput(
            semantic = if (isRightClick) SemanticInteraction.SECONDARY_WORLD_ACTION
                else SemanticInteraction.PRIMARY_WORLD_ACTION,
            source = "fabric:packet",
            physicalKey = "click:${if (isRightClick) "right" else "left"}",
            sameSourceCooldownNanos = 200_000_000L,
        )
        val candidates = buildList {
            if (playerUUID !in uiSuppressed && getSurfaces(playerUUID).isNotEmpty()) {
                add(InteractionCandidate(InteractionLayer.SURFACE, "surface") {
                    handleSurfaceClick(playerUUID, isRightClick)
                })
            }
            closestAction?.let { action ->
                add(InteractionCandidate(InteractionLayer.OVERLAY, "overlay") {
                    action.invoke()
                    true
                })
            }
        }
        val decision = InteractionContexts.forPlayer(playerUUID).dispatch(input, candidates)
        if (debug) {
            val label = when (decision.outcome) {
                InteractionOutcome.CONSUMED -> if (decision.consumedBy == "overlay") closestLabel else "$side:${decision.consumedBy}"
                InteractionOutcome.DEDUPLICATED -> "$side:deduplicated"
                InteractionOutcome.MISSED -> "$side:miss"
            }
            logger.info(
                "[debug $playerUUID] $label on ${Thread.currentThread().name}; " +
                    "attempted=${decision.attempted.joinToString()}"
            )
            onDebugClick?.invoke(playerUUID, label)
        }
        return decision.consumed
    }

    /** Clean up per-player state on disconnect. */
    fun cleanupPlayer(playerUUID: UUID) {
        InteractionContexts.remove(playerUUID)
        uiSuppressed.remove(playerUUID)
        lastConsumedLeftClick.remove(playerUUID)
        debugPlayers.remove(playerUUID)
        // A dropped host without close() leaves its entity alive client-side
        // until the player relogs — close every surface the player still has
        // open before discarding the entry.
        activeSurfaces.remove(playerUUID)?.forEach { it.close() }
        io.schemat.displaykit.surface.SurfaceFocus.clear(playerUUID)
    }

    fun closeAll() {
        activeOverlays.values.flatten().toList().forEach { it.destroy() }
        // Surfaces too, for the same reason cleanupPlayer closes them: a host
        // dropped without close() strands its entity client-side until the
        // player relogs. Shutdown is exactly when that is least recoverable.
        val players = activeSurfaces.keys.toList()
        activeSurfaces.values.flatten().toList().forEach { it.close() }
        activeSurfaces.clear()
        // close() no longer clears SurfaceFocus itself (that's per-viewer, not
        // per-host -- see unregisterSurface), so this loop has to do it for
        // every player being torn down here, same as cleanupPlayer does.
        players.forEach { io.schemat.displaykit.surface.SurfaceFocus.clear(it) }
        InteractionContexts.clear()
    }

}
