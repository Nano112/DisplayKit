package io.schemat.displaykit.ui

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

object InteractionRouter {
    private val logger = Logger.getLogger("displaykit/router")
    private val activeUIs = ConcurrentHashMap<UUID, MutableList<FloatingUI>>()
    private val activeOverlays = ConcurrentHashMap<UUID, MutableList<WorldOverlay>>()
    private val activeSurfaces = ConcurrentHashMap<UUID, MutableList<io.schemat.displaykit.surface.SurfaceHost>>()
    private val debugPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val lastClickTime = ConcurrentHashMap<String, Long>()
    private val lastConsumedLeftClick = ConcurrentHashMap<UUID, Long>()

    /**
     * Players whose FloatingUIs should NOT receive clicks — set while a tool
     * flow (Place/Move/Edit) is active so panels hovering over buildings
     * (e.g. the island terminal above the pavilion) can't swallow the clicks
     * meant for the world. Overlays still dispatch normally.
     */
    private val uiSuppressed = ConcurrentHashMap.newKeySet<UUID>()

    fun setUiSuppressed(playerUUID: UUID, suppressed: Boolean) {
        if (suppressed) uiSuppressed.add(playerUUID) else uiSuppressed.remove(playerUUID)
    }
    private const val CLICK_COOLDOWN_MS = 200L
    private const val CONSUMED_WINDOW_MS = 100L

    /** Optional callback for debug click info — set by server module. */
    var onDebugClick: ((playerUUID: UUID, info: String) -> Unit)? = null

    fun setDebug(playerUUID: UUID, enabled: Boolean) {
        if (enabled) debugPlayers.add(playerUUID) else debugPlayers.remove(playerUUID)
    }

    fun isDebug(playerUUID: UUID): Boolean = playerUUID in debugPlayers

    fun registerUI(playerUUID: UUID, ui: FloatingUI) {
        activeUIs.getOrPut(playerUUID) { mutableListOf() }.add(ui)
    }

    fun unregisterUI(playerUUID: UUID, ui: FloatingUI) {
        val list = activeUIs[playerUUID] ?: return
        list.remove(ui)
        if (list.isEmpty()) activeUIs.remove(playerUUID)
    }

    fun registerOverlay(playerUUID: UUID, overlay: WorldOverlay) {
        activeOverlays.getOrPut(playerUUID) { mutableListOf() }.add(overlay)
    }

    fun unregisterOverlay(playerUUID: UUID, overlay: WorldOverlay) {
        val list = activeOverlays[playerUUID] ?: return
        list.remove(overlay)
        if (list.isEmpty()) activeOverlays.remove(playerUUID)
    }

    fun hasActiveUI(playerUUID: UUID): Boolean {
        return activeUIs[playerUUID]?.isNotEmpty() == true
    }

    fun getUIs(playerUUID: UUID): List<FloatingUI> {
        return activeUIs[playerUUID]?.toList() ?: emptyList()
    }

    fun getOverlaysForPlayer(playerUUID: UUID): List<WorldOverlay> {
        return activeOverlays[playerUUID]?.toList() ?: emptyList()
    }

    fun registerSurface(playerUUID: UUID, host: io.schemat.displaykit.surface.SurfaceHost) {
        activeSurfaces.computeIfAbsent(playerUUID) { mutableListOf() }.add(host)
    }

    fun unregisterSurface(playerUUID: UUID, host: io.schemat.displaykit.surface.SurfaceHost) {
        activeSurfaces[playerUUID]?.remove(host)
        if (activeSurfaces[playerUUID]?.isEmpty() == true) activeSurfaces.remove(playerUUID)
    }

    fun getSurfaces(playerUUID: UUID): List<io.schemat.displaykit.surface.SurfaceHost> =
        activeSurfaces[playerUUID]?.toList() ?: emptyList()

    /** Returns true when a surface consumed the click. */
    fun handleSurfaceClick(playerUUID: UUID): Boolean =
        getSurfaces(playerUUID).any { it.handleClick() }

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

    /** Check if the player is looking at any UI's bounding area. */
    fun isPlayerLookingAtUI(playerUUID: UUID): Boolean {
        val uis = activeUIs[playerUUID] ?: return false
        return uis.any { !it.isDestroyed() && it.isPlayerLookingAt() }
    }

    /**
     * Check if player is targeting any interactive surface (overlay or UI).
     * Used by mixins to gate block-breaking/entity actions without dispatching clicks.
     */
    fun isTargetingInteractive(playerUUID: UUID): Boolean {
        return hasHoveredOverlay(playerUUID) || isPlayerLookingAtUI(playerUUID)
    }

    /**
     * Central left-click handler. Called from ServerboundSwingPacket mixin.
     * Debounces, then raycasts all UIs and overlays, dispatching to the closest hit. Returns true if consumed.
     */
    fun onLeftClick(playerUUID: UUID): Boolean {
        if (!tryClick(playerUUID, isRightClick = false)) return false
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
        if (!tryClick(playerUUID, isRightClick = true)) return false
        return dispatchClick(playerUUID, isRightClick = true)
    }

    /**
     * Returns true if click is allowed (not debounced). Cooldowns are
     * PER SIDE (a right-click must not eat the following left-click) and the
     * timestamp is recorded ONLY when allowed — recording rejected attempts
     * perpetually renewed the window, eating whole click bursts.
     */
    private fun tryClick(playerUUID: UUID, isRightClick: Boolean): Boolean {
        val key = "$playerUUID:${if (isRightClick) "R" else "L"}"
        val now = System.currentTimeMillis()
        val last = lastClickTime[key] ?: 0L
        val allowed = (now - last) >= CLICK_COOLDOWN_MS
        if (allowed) lastClickTime[key] = now
        if (!allowed && isDebug(playerUUID)) {
            logger.info("[debug $playerUUID] click DEBOUNCED (${now - last}ms)")
        }
        return allowed
    }

    /** Raycast all UIs and overlays, dispatch to the closest hit along the ray. */
    private fun dispatchClick(playerUUID: UUID, isRightClick: Boolean): Boolean {
        val debug = isDebug(playerUUID)
        val side = if (isRightClick) "R" else "L"

        // A surface is a foreground window, so it consumes the click before UI/overlay
        // dispatch even gets a chance to raycast.
        if (handleSurfaceClick(playerUUID)) {
            if (debug) onDebugClick?.invoke(playerUUID, "$side:surface")
            return true
        }

        var closestDist = Double.MAX_VALUE
        var closestAction: (() -> Unit)? = null
        var closestLabel = ""

        // Collect UI hits (skipped while a tool flow owns the clicks)
        val uis = if (playerUUID in uiSuppressed) null else activeUIs[playerUUID]
        if (uis != null) {
            for (ui in uis.toList()) {
                if (ui.isDestroyed()) continue
                val dist = ui.hitDistance() ?: continue
                if (dist < closestDist) {
                    closestDist = dist
                    closestAction = { ui.handleClick(isRightClick) }
                    closestLabel = "$side:ui"
                }
            }
        }

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

        if (closestAction != null) {
            closestAction.invoke()
            if (debug) onDebugClick?.invoke(playerUUID, closestLabel)
            return true
        }

        if (debug) onDebugClick?.invoke(playerUUID, "$side:miss")
        return false
    }

    /** Clean up per-player state on disconnect. */
    fun cleanupPlayer(playerUUID: UUID) {
        lastClickTime.remove("$playerUUID:L")
        lastClickTime.remove("$playerUUID:R")
        uiSuppressed.remove(playerUUID)
        lastConsumedLeftClick.remove(playerUUID)
        debugPlayers.remove(playerUUID)
        // A dropped host without close() leaves its entity alive client-side
        // until the player relogs — close every surface the player still has
        // open before discarding the entry.
        activeSurfaces.remove(playerUUID)?.forEach { it.close() }
    }

    fun closeAll() {
        activeUIs.values.flatten().toList().forEach { it.destroy() }
        activeOverlays.values.flatten().toList().forEach { it.destroy() }
    }

    fun closeForPlayer(playerUUID: UUID) {
        activeUIs[playerUUID]?.toList()?.forEach { it.destroy() }
        // Note: overlays are NOT closed here — they have their own lifecycle
    }

    fun closeAllForPlayer(playerUUID: UUID) {
        activeUIs[playerUUID]?.toList()?.forEach { it.destroy() }
        activeOverlays[playerUUID]?.toList()?.forEach { it.destroy() }
    }
}
