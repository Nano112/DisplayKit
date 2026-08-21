package io.schemat.displaykit.fabric.surface

import io.schemat.displaykit.ui.InteractionRouter
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents

/**
 * Drives surface pointers, hover and drag once per server tick.
 *
 * Surfaces had no tick loop at all, which is why `SurfaceHost.tickHover()` sat
 * unused: nothing ever called it.
 */
object SurfaceTicker {

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ServerTickEvents.END_SERVER_TICK.register { server ->
            for (player in server.playerList.players) {
                val hosts = InteractionRouter.getSurfaces(player.uuid)
                if (hosts.isEmpty()) continue
                for (host in hosts) {
                    try {
                        host.tick()
                    } catch (e: Exception) {
                        // One bad surface must not stop the tick for everyone
                        // else, and must not spam: the host keeps its state, so
                        // the next tick retries.
                        LOGGER.warning("Surface tick failed for ${player.uuid}: ${e.message}")
                    }
                }
            }
        }
    }

    private val LOGGER = java.util.logging.Logger.getLogger("DisplayKit-SurfaceTicker")
}
