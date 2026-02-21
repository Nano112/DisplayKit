package io.schemat.displaykit.ui

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

object InteractionRouter {
    private val logger = Logger.getLogger("displaykit/router")
    private val activeUIs = ConcurrentHashMap<UUID, MutableList<FloatingUI>>()
    private val activeOverlays = ConcurrentHashMap<UUID, MutableList<WorldOverlay>>()
    private val debugPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val lastClickTime = ConcurrentHashMap<UUID, Long>()
    private val lastConsumedLeftClick = ConcurrentHashMap<UUID, Long>()
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
        if (!tryClick(playerUUID)) return false
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
        if (!tryClick(playerUUID)) return false
        return dispatchClick(playerUUID, isRightClick = true)
    }

    /** Returns true if click is allowed (not debounced). */
    private fun tryClick(playerUUID: UUID): Boolean {
        val now = System.currentTimeMillis()
        val last = lastClickTime.put(playerUUID, now) ?: 0L
        val allowed = (now - last) >= CLICK_COOLDOWN_MS
        if (!allowed && isDebug(playerUUID)) {
            logger.info("[debug $playerUUID] click DEBOUNCED (${now - last}ms)")
        }
        return allowed
    }

    /** Raycast all UIs and overlays, dispatch to the closest hit along the ray. */
    private fun dispatchClick(playerUUID: UUID, isRightClick: Boolean): Boolean {
        val debug = isDebug(playerUUID)
        val side = if (isRightClick) "R" else "L"

        var closestDist = Double.MAX_VALUE
        var closestAction: (() -> Unit)? = null
        var closestLabel = ""

        // Collect UI hits
        val uis = activeUIs[playerUUID]
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
        lastClickTime.remove(playerUUID)
        lastConsumedLeftClick.remove(playerUUID)
        debugPlayers.remove(playerUUID)
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
