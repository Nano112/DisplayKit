package io.schemat.displaykit.region

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

interface AnimationListener {
    fun onStart(animator: RegionAnimator) {}
    fun onTick(animator: RegionAnimator, tick: Int) {}
    fun onComplete(animator: RegionAnimator) {}
}

enum class AnimatorState {
    IDLE, PLAYING, PAUSED, COMPLETED
}

/**
 * Manages playback of a region animation.
 *
 * Uses client-side interpolation between keyframes: sends one animateTransform
 * per keyframe segment and lets the client GPU smoothly interpolate between them.
 * Only sends packets at keyframe boundaries — zero packets in between.
 *
 * Supports deferred spawn: the display is not spawned until [spawnTick], and
 * completes at [completionTick] (both derived from keyframe times).
 * This allows components to start/end at different times within the overall timeline.
 *
 * @param animation The animation to play
 * @param displayManager The display manager (real or virtual entities)
 */
class RegionAnimator(
    val animation: RegionAnimation,
    val displayManager: RegionDisplayManager
) {
    var state: AnimatorState = AnimatorState.IDLE
        private set

    var currentTick: Int = 0
        private set

    val progress: Float get() = currentTick.toFloat() / animation.durationTicks
    val isPlaying: Boolean get() = state == AnimatorState.PLAYING
    val isCompleted: Boolean get() = state == AnimatorState.COMPLETED

    private val listeners = CopyOnWriteArrayList<AnimationListener>()

    /** Ticks to keep alive after completion (for overlap transitions). */
    var postCompletionDelay: Int = 0
    private var postCompletionTicks: Int = 0

    /** Pre-computed tick boundaries where we need to send a new transform. */
    private var segmentTicks: IntArray = IntArray(0)
    private var nextSegmentIndex: Int = 0

    /** Tick when display should spawn (first keyframe time). */
    var spawnTick: Int = 0
        private set

    /** Tick when animation completes (last keyframe time). */
    var completionTick: Int = 0
        private set

    private var spawned = false

    fun addListener(listener: AnimationListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AnimationListener) {
        listeners.remove(listener)
    }

    /**
     * Start the animation timeline. Display spawn is deferred until [spawnTick].
     */
    fun play() {
        if (state == AnimatorState.PLAYING) return

        if (state == AnimatorState.IDLE || state == AnimatorState.COMPLETED) {
            currentTick = 0
            spawned = false
            buildSegmentTicks()
        }

        state = AnimatorState.PLAYING
        listeners.forEach { it.onStart(this) }
    }

    fun pause() {
        if (state == AnimatorState.PLAYING) {
            state = AnimatorState.PAUSED
        }
    }

    fun resume() {
        if (state == AnimatorState.PAUSED) {
            state = AnimatorState.PLAYING
        }
    }

    fun stop() {
        if (spawned) {
            displayManager.destroy()
            spawned = false
        }
        state = AnimatorState.IDLE
        currentTick = 0
        nextSegmentIndex = 0
    }

    /**
     * Build the tick values at which each keyframe occurs.
     */
    private fun buildSegmentTicks() {
        val kfs = animation.keyframes
        segmentTicks = IntArray(kfs.size) { i ->
            (kfs[i].time * animation.durationTicks).toInt()
        }
        nextSegmentIndex = 0
        spawnTick = segmentTicks.firstOrNull() ?: 0
        completionTick = segmentTicks.lastOrNull() ?: animation.durationTicks
    }

    /**
     * Advance the animation by one tick.
     * Sends animateTransform only at keyframe boundaries.
     */
    fun tick(): Boolean {
        // Post-completion countdown: keep alive for overlap transitions
        if (state == AnimatorState.COMPLETED) {
            postCompletionTicks++
            if (postCompletionTicks >= postCompletionDelay) {
                stop()
                return false
            }
            return true
        }

        if (state != AnimatorState.PLAYING) return false

        currentTick++

        // Deferred spawn: spawn display when we reach the first keyframe's tick
        if (!spawned && currentTick >= spawnTick) {
            spawned = true
            displayManager.spawn()
            // Start targeting KF1 (KF0 is the spawn position)
            nextSegmentIndex = 1
            // Skip transform this tick — client needs 1 tick to process the spawn
            listeners.forEach { it.onTick(this, currentTick) }
            return true
        }

        // Send transforms at keyframe boundaries
        if (spawned) {
            while (nextSegmentIndex < segmentTicks.size &&
                   currentTick >= segmentTicks[nextSegmentIndex - 1]) {
                sendSegmentTransform(nextSegmentIndex)
                nextSegmentIndex++
            }
        }

        listeners.forEach { it.onTick(this, currentTick) }

        // Complete at the last keyframe's tick, not the overall duration
        if (currentTick >= completionTick) {
            state = AnimatorState.COMPLETED
            listeners.forEach { it.onComplete(this) }
            if (postCompletionDelay <= 0) {
                stop()
                return false
            }
            return true // stay alive for overlap
        }

        return true
    }

    /**
     * Send an animateTransform targeting the given keyframe index,
     * with duration equal to the ticks in this segment.
     * Skips "hold" segments where position/rotation/scale haven't changed,
     * since sending identical transformation data would re-trigger the previous interpolation.
     */
    private fun sendSegmentTransform(targetKfIndex: Int) {
        val kfs = animation.keyframes
        if (targetKfIndex >= kfs.size) return

        val targetKf = kfs[targetKfIndex]
        val prevKf = kfs[targetKfIndex - 1]

        // Skip if target matches previous — a "hold" segment needs no packet.
        // Sending would cause SynchedEntityData to skip the transformation (same value = not dirty)
        // while still sending start_interpolation, causing the client to re-play the previous segment.
        if (targetKf.position == prevKf.position &&
            targetKf.scale == prevKf.scale &&
            targetKf.rotation == prevKf.rotation) {
            return
        }

        val prevTick = segmentTicks[targetKfIndex - 1]
        val targetTick = segmentTicks[targetKfIndex]
        val segmentDuration = maxOf(1, targetTick - prevTick)

        displayManager.animateTransform(
            targetPosition = targetKf.position,
            targetRotation = targetKf.rotation,
            targetScale = targetKf.scale,
            durationTicks = segmentDuration
        )
    }

    fun addViewer(uuid: UUID) {
        displayManager.addViewer(uuid)
    }

    fun removeViewer(uuid: UUID) {
        displayManager.removeViewer(uuid)
    }
}
