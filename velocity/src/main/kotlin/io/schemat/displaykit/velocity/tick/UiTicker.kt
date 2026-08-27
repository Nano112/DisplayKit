package io.schemat.displaykit.velocity.tick

import com.velocitypowered.api.proxy.ProxyServer
import io.schemat.displaykit.animation.AnimationTicker
import io.schemat.displaykit.surface.SurfaceHost
import io.schemat.displaykit.ui.InteractionRouter
import io.schemat.displaykit.velocity.thread.UiOwnerThread
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * The 50 ms loop standing in for the server tick: animations advance, then
 * every open surface raycasts its pointer, exactly what fabric's
 * SurfaceTicker does on END_SERVER_TICK.
 */
class UiTicker(
    private val proxy: ProxyServer,
    private val uiThread: UiOwnerThread,
    private val logger: Logger,
) : AutoCloseable {

    // Identity-keyed and weak, so a host that closes while broken can still
    // be collected instead of leaking here; first failure logs, the rest are
    // suppressed to keep a throwing widget from filling the log at 20 lines
    // a second.
    private val loggedFailures: MutableSet<SurfaceHost> =
        Collections.newSetFromMap(WeakHashMap())

    private var task: ScheduledFuture<*>? = null

    fun start() {
        check(task == null) { "UiTicker already started" }
        task = uiThread.executor.scheduleAtFixedRate(::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        try {
            AnimationTicker.tick()
        } catch (e: Exception) {
            logger.warning("Animation tick failed: ${e.message}")
        }

        for (player in proxy.allPlayers) {
            val hosts = InteractionRouter.getSurfaces(player.uniqueId)
            if (hosts.isEmpty()) continue
            for (host in hosts) {
                try {
                    host.tick()
                } catch (e: Exception) {
                    // One bad surface must not stop the tick for everyone else
                    if (loggedFailures.add(host)) {
                        logger.warning(
                            "Surface tick failed for ${player.uniqueId}: ${e.message} " +
                                "(further failures on this surface will be suppressed)"
                        )
                    }
                }
            }
        }
    }

    override fun close() {
        task?.cancel(false)
        task = null
    }

    private companion object {
        const val TICK_MILLIS = 50L
    }
}
