package io.schemat.displaykit.surface

import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.state.StateInvalidationListener
import io.schemat.displaykit.state.StateScope
import io.schemat.displaykit.ui.InteractionRouter

/** Lifecycle policy shared by world-anchored surfaces. */
data class SurfaceLifecyclePolicy(
    val maxDistance: Double = 15.0,
    val timeoutTicks: Int = 1_200,
    val closeWhenOwnerOffline: Boolean = true
) {
    init {
        require(maxDistance > 0.0) { "maxDistance must be positive (got $maxDistance)." }
        require(timeoutTicks > 0) { "timeoutTicks must be positive (got $timeoutTicks)." }
    }

    companion object {
        /** No distance or time expiry; owner disconnect still closes it. */
        @JvmField
        val PERSISTENT = SurfaceLifecyclePolicy(
            maxDistance = Double.POSITIVE_INFINITY,
            timeoutTicks = Int.MAX_VALUE
        )
    }
}

enum class SurfaceCloseReason {
    MANUAL,
    OWNER_OFFLINE,
    OUT_OF_RANGE,
    TIMEOUT,
    ANCHOR_MISSING
}

/**
 * Owns one world surface from anchor resolution through interaction routing
 * and teardown.
 *
 * Feature code supplies a painted/layout-backed [Surface] and an [anchor]. The
 * session centres it, opens its [SurfaceHost], registers it with the router,
 * follows a dynamic anchor, enforces lifecycle policy, and performs idempotent
 * cleanup. This is the boundary that replaces repeated `open + register +
 * unregister + close` code in product windows.
 */
class WorldSurfaceSession(
    platform: PlatformProvider,
    private val owner: PlayerRef,
    val surface: Surface,
    private val anchor: SurfaceAnchor,
    private val lifecycle: SurfaceLifecyclePolicy = SurfaceLifecyclePolicy(),
    private val onTick: () -> Unit = {},
    private val onClosed: (SurfaceCloseReason) -> Unit = {}
) : AutoCloseable {

    enum class State { NEW, OPEN, CLOSED }

    val host: SurfaceHost = SurfaceHost(platform, owner, surface)

    var state: State = State.NEW
        private set

    var closeReason: SurfaceCloseReason? = null
        private set

    val isOpen: Boolean get() = state == State.OPEN

    private var ticksAlive = 0
    private var lastPose: SurfacePose? = null
    private val stateBindings = mutableListOf<StateBinding>()

    private inner class StateBinding(private val upstream: AutoCloseable) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            upstream.close()
            stateBindings.indexOfFirst { it === this }
                .takeIf { it >= 0 }
                ?.let(stateBindings::removeAt)
        }
    }

    init {
        host.lifecycleTick = ::beforeHostTick
    }

    /**
     * Paint, place, spawn, and register this session. Idempotent while open;
     * a closed session is terminal and cannot be reopened.
     */
    fun open(): Boolean {
        if (state == State.OPEN) return true
        check(state == State.NEW) { "A closed WorldSurfaceSession cannot be reopened." }

        if (lifecycle.closeWhenOwnerOffline && !owner.isOnline()) {
            finishWithoutOpening(SurfaceCloseReason.OWNER_OFFLINE)
            return false
        }
        val pose = anchor.resolve(owner)
        if (pose == null) {
            finishWithoutOpening(SurfaceCloseReason.ANCHOR_MISSING)
            return false
        }
        if (isOutOfRange(pose)) {
            finishWithoutOpening(SurfaceCloseReason.OUT_OF_RANGE)
            return false
        }

        applyPose(pose)
        if (surface.root != null) surface.paintTree()
        host.open()
        InteractionRouter.registerSurface(owner.uuid, host)
        state = State.OPEN
        return true
    }

    /** Repaint the current tree while preserving reconciled entity identity. */
    fun repaint(): Boolean {
        if (state != State.OPEN) return false
        if (surface.root != null) surface.paintTree()
        host.repaint()
        return true
    }

    /** Repaint once after each coalesced invalidation from [scope]. */
    fun bind(scope: StateScope): AutoCloseable {
        check(state != State.CLOSED) { "Cannot bind state to a closed WorldSurfaceSession." }
        val binding = StateBinding(
            scope.onInvalidated(StateInvalidationListener { repaint() })
        )
        stateBindings += binding
        return binding
    }

    override fun close() {
        close(SurfaceCloseReason.MANUAL)
    }

    fun close(reason: SurfaceCloseReason) {
        if (state == State.CLOSED) return
        val wasOpen = state == State.OPEN
        state = State.CLOSED
        closeReason = reason
        host.lifecycleTick = null
        stateBindings.toList().asReversed().forEach { it.close() }
        if (wasOpen) InteractionRouter.unregisterSurface(owner.uuid, host)
        host.close()
        onClosed(reason)
    }

    /** Runs before pointer work from [SurfaceHost.tick]. */
    private fun beforeHostTick(): Boolean {
        if (state != State.OPEN) return false
        if (lifecycle.closeWhenOwnerOffline && !owner.isOnline()) {
            close(SurfaceCloseReason.OWNER_OFFLINE)
            return false
        }

        ticksAlive++
        if (ticksAlive >= lifecycle.timeoutTicks) {
            close(SurfaceCloseReason.TIMEOUT)
            return false
        }

        val pose = anchor.resolve(owner)
        if (pose == null) {
            close(SurfaceCloseReason.ANCHOR_MISSING)
            return false
        }
        if (isOutOfRange(pose)) {
            close(SurfaceCloseReason.OUT_OF_RANGE)
            return false
        }

        if (pose != lastPose) {
            applyPose(pose)
            host.repaint()
        }
        onTick()
        return state == State.OPEN
    }

    private fun isOutOfRange(pose: SurfacePose): Boolean =
        owner.eyePosition().distanceSquared(pose.center) > lifecycle.maxDistance * lifecycle.maxDistance

    private fun applyPose(pose: SurfacePose) {
        val worldWidth =
            (surface.widthPx * surface.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        val worldHeight =
            (surface.heightPx * surface.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        surface.yawDegrees = pose.yawDegrees
        surface.position = SurfacePlacement.centeredOrigin(
            pose.center,
            pose.yawDegrees,
            worldWidth,
            worldHeight
        )
        lastPose = pose
    }

    private fun finishWithoutOpening(reason: SurfaceCloseReason) {
        state = State.CLOSED
        closeReason = reason
        host.lifecycleTick = null
        onClosed(reason)
    }
}
