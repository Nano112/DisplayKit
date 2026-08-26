package io.schemat.displaykit.fabric.surface

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.fabric.pack.PackSync
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.state.StateScope
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceAnchor
import io.schemat.displaykit.surface.SurfaceCloseReason
import io.schemat.displaykit.surface.SurfaceLifecyclePolicy
import io.schemat.displaykit.surface.WorldSurfaceSession
import java.util.concurrent.atomic.AtomicLong

/**
 * Fabric's complete presentation lifecycle for a retained world surface.
 *
 * Construct the tree, create this presentation, finish wiring consumer state,
 * then call [present]. It pre-paints generated glyphs, synchronizes the pack,
 * opens only after the client applies it, binds reactive state, and always
 * releases diagnostics on close.
 */
class FabricSurfacePresentation(
    private val owner: PlayerRef,
    surface: Surface,
    anchor: SurfaceAnchor,
    lifecycle: SurfaceLifecyclePolicy = SurfaceLifecyclePolicy(),
    stateScope: StateScope? = null,
    diagnosticLabel: String = "surface",
    onTick: () -> Unit = {},
    onClosed: (SurfaceCloseReason) -> Unit = {},
) : AutoCloseable {
    private val packKey = "$diagnosticLabel:${owner.uuid}:${nextId.incrementAndGet()}"
    private var started = false

    val session = WorldSurfaceSession(
        platform = DisplayKit.platform,
        owner = owner,
        surface = surface,
        anchor = anchor,
        lifecycle = lifecycle,
        onTick = onTick,
        onClosed = { reason ->
            PackSync.forget(packKey)
            onClosed(reason)
        },
    ).also { world -> stateScope?.let(world::bind) }

    /** Begin pack synchronization and eventually open the surface. */
    fun present(): WorldSurfaceSession {
        check(!started) { "A FabricSurfacePresentation can only be presented once" }
        check(session.state == WorldSurfaceSession.State.NEW) { "Cannot present a closed surface" }
        started = true
        PackSync.withPackSync(packKey) { session.surface.paintTree() }
        FabricPackIntegration.whenPackApplied(owner.uuid) {
            if (session.state == WorldSurfaceSession.State.NEW) {
                // Settle first: open can synchronously reject an invalid anchor
                // and close, whose cleanup must be the final budget operation.
                PackSync.settled(packKey)
                session.open()
            } else {
                PackSync.forget(packKey)
            }
        }
        return session
    }

    override fun close() = session.close()

    private companion object {
        val nextId = AtomicLong()
    }
}
