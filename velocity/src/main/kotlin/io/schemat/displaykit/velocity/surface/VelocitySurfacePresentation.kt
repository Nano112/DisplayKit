package io.schemat.displaykit.velocity.surface

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.state.StateScope
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceAnchor
import io.schemat.displaykit.surface.SurfaceCloseReason
import io.schemat.displaykit.surface.SurfaceLifecyclePolicy
import io.schemat.displaykit.surface.WorldSurfaceSession
import io.schemat.displaykit.velocity.VelocityDisplayKit

/**
 * The Velocity presentation lifecycle for a retained world surface.
 *
 * The platform runs pack-free for now (ENTITIES render mode), so unlike
 * fabric's presentation there is no pack synchronisation to wait for:
 * present() paints and opens in one hop onto the UI owner thread. The class
 * exists so consumers write the same shape of code on both platforms and so
 * the pack ritual can slot back in here when the pack pipeline is ported.
 */
class VelocitySurfacePresentation(
    owner: PlayerRef,
    surface: Surface,
    anchor: SurfaceAnchor,
    lifecycle: SurfaceLifecyclePolicy = SurfaceLifecyclePolicy(),
    stateScope: StateScope? = null,
    @Suppress("unused") private val diagnosticLabel: String = "surface",
    onTick: () -> Unit = {},
    onClosed: (SurfaceCloseReason) -> Unit = {},
) : AutoCloseable {

    private var started = false

    val session = WorldSurfaceSession(
        platform = DisplayKit.platform,
        owner = owner,
        surface = surface,
        anchor = anchor,
        lifecycle = lifecycle,
        onTick = onTick,
        onClosed = onClosed,
    ).also { world -> stateScope?.let(world::bind) }

    /** Paints and opens the surface on the UI owner thread. */
    fun present(): WorldSurfaceSession {
        check(!started) { "A VelocitySurfacePresentation can only be presented once" }
        check(session.state == WorldSurfaceSession.State.NEW) { "Cannot present a closed surface" }
        started = true

        VelocityDisplayKit.requireInstance().uiThread.dispatch {
            if (session.state == WorldSurfaceSession.State.NEW) {
                session.surface.paintTree()
                session.open()
            }
        }
        return session
    }

    override fun close() {
        VelocityDisplayKit.requireInstance().uiThread.dispatch { session.close() }
    }
}
