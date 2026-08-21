package io.schemat.displaykit.fabric.surface

import io.schemat.displaykit.ui.InteractionRouter
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents

/**
 * Drives surface pointers, hover and drag once per server tick.
 *
 * Surfaces had no tick loop at all, which is why an earlier dead
 * `SurfaceHost.tickHover()` (since deleted -- `tick()` and [SurfaceFocus]
 * supersede it) sat unused: nothing ever called it.
 */
object SurfaceTicker {

    private var registered = false

    // A host that keeps throwing must log its FIRST failure loudly (so it is
    // noticed) and then stay quiet -- without this, a widget throwing every
    // tick emits 20 log lines a second forever. A WeakHashMap (SurfaceHost
    // has no equals/hashCode override, so this is identity-keyed already)
    // rather than a plain HashSet so a host that later closes can still be
    // garbage-collected instead of leaking here for the life of the process.
    private val loggedFailures: MutableSet<io.schemat.displaykit.surface.SurfaceHost> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

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
                        // the next tick retries, but only the first failure is
                        // logged -- otherwise a widget throwing every tick is
                        // 20 log lines a second forever.
                        if (loggedFailures.add(host)) {
                            LOGGER.warning(
                                "Surface tick failed for ${player.uuid}: ${e.message} " +
                                    "(further failures on this surface will be suppressed)"
                            )
                        }
                    }
                }
            }
        }
    }

    private val LOGGER = java.util.logging.Logger.getLogger("DisplayKit-SurfaceTicker")
}
