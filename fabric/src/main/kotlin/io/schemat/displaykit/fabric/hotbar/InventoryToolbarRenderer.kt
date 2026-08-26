package io.schemat.displaykit.fabric.hotbar

import io.schemat.displaykit.action.ActionMenuSession
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Public Fabric renderer for [ActionMenuSession].
 *
 * Application state lives in the action session; this object only owns the
 * physical inventory presentation, input arbitration, and crash-safe stash.
 */
object InventoryToolbarRenderer {

    @JvmStatic
    @JvmOverloads
    fun present(
        player: ServerPlayer,
        actions: ActionMenuSession,
        onDismissed: (() -> Unit)? = null
    ) = HotbarMenu.open(player, actions, onDismissed)

    @JvmStatic
    fun dismiss(player: ServerPlayer) = HotbarMenu.close(player)

    @JvmStatic
    fun isPresented(playerId: UUID): Boolean = HotbarMenu.isOpen(playerId)

    @JvmStatic
    fun session(playerId: UUID): ActionMenuSession? = HotbarMenu.actionSession(playerId)

    @JvmStatic
    @JvmOverloads
    fun activateSelected(playerId: UUID, source: String = "arbiter") =
        HotbarMenu.pressSelected(playerId, source)

    @JvmStatic
    fun select(playerId: UUID, index: Int) = HotbarMenu.selectSlot(playerId, index)

    @JvmStatic
    fun activateSelectedNavigation(playerId: UUID, source: String = "arbiter"): Boolean =
        HotbarMenu.pressSelectedNavigation(playerId, source)

    /** Frozen-menu escape hatch: reset to root, then dismiss on a quick repeat. */
    @JvmStatic
    fun reset(playerId: UUID) = HotbarMenu.sneakReset(playerId)

    /** Optional instrumentation hooks for host applications. */
    @JvmStatic
    var perfTime: ((String, Long) -> Unit)?
        get() = HotbarMenu.perfTime
        set(value) { HotbarMenu.perfTime = value }

    @JvmStatic
    var perfCount: ((String, Long) -> Unit)?
        get() = HotbarMenu.perfCount
        set(value) { HotbarMenu.perfCount = value }
}
