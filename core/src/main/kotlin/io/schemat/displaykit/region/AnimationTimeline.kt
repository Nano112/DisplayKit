package io.schemat.displaykit.region

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

interface TimelineListener {
    fun onAllComplete(timeline: AnimationTimeline) {}
}

/**
 * Orchestrates multiple region animations.
 *
 * @param displayManagerFactory Creates a [RegionDisplayManager] for each animation's capture
 */
class AnimationTimeline(
    private val displayManagerFactory: (RegionCapture) -> RegionDisplayManager
) {
    private val animators = CopyOnWriteArrayList<RegionAnimator>()
    private val listeners = CopyOnWriteArrayList<TimelineListener>()

    var isPlaying: Boolean = false
        private set

    val animationCount: Int get() = animators.size

    fun add(animation: RegionAnimation): RegionAnimator {
        val displayManager = displayManagerFactory(animation.capture)
        val animator = RegionAnimator(animation, displayManager)
        animators.add(animator)
        return animator
    }

    fun remove(animator: RegionAnimator) {
        animator.stop()
        animators.remove(animator)
    }

    fun clear() {
        animators.forEach { it.stop() }
        animators.clear()
    }

    fun addViewer(uuid: UUID) {
        animators.forEach { it.addViewer(uuid) }
    }

    fun removeViewer(uuid: UUID) {
        animators.forEach { it.removeViewer(uuid) }
    }

    fun addListener(listener: TimelineListener) {
        listeners.add(listener)
    }

    fun play() {
        if (isPlaying) return
        isPlaying = true
        animators.forEach { it.play() }
    }

    fun pause() {
        isPlaying = false
        animators.forEach { it.pause() }
    }

    fun resume() {
        isPlaying = true
        animators.forEach { it.resume() }
    }

    fun stop() {
        isPlaying = false
        animators.forEach { it.stop() }
    }

    fun tick(): Boolean {
        if (!isPlaying) return false

        var anyPlaying = false
        for (animator in animators) {
            if (animator.tick()) {
                anyPlaying = true
            }
        }

        if (!anyPlaying) {
            isPlaying = false
            listeners.forEach { it.onAllComplete(this) }
        }

        return anyPlaying
    }

    fun isAllCompleted(): Boolean = animators.all { it.isCompleted }
    fun getPlayingAnimators(): List<RegionAnimator> = animators.filter { it.isPlaying }
    fun getAnimators(): List<RegionAnimator> = animators.toList()
}
