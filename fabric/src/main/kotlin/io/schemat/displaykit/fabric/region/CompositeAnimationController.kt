package io.schemat.displaykit.fabric.region

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3i
import io.schemat.displaykit.region.*
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Listener for composite animation lifecycle events.
 */
interface CompositeAnimationListener {
    fun onStart(controller: CompositeAnimationController) {}
    fun onComplete(controller: CompositeAnimationController) {}
    fun onCancel(controller: CompositeAnimationController) {}
}

/**
 * Fabric-specific lifecycle manager for [CompositeAnimation].
 *
 * Orchestrates: capture → clear source → animate → place at destination.
 * Uses [AnimationTimeline] internally for coordinated multi-animator playback.
 *
 * @param animation The composite animation to play
 * @param level The server level for entity spawning and block access
 */
class CompositeAnimationController(
    val animation: CompositeAnimation,
    private val level: ServerLevel
) {
    enum class State { IDLE, PLAYING, COMPLETED }

    var state: State = State.IDLE
        private set

    private val listeners = CopyOnWriteArrayList<CompositeAnimationListener>()
    private val worldAccess = FabricRegionWorldAccess.of(level)

    // Binding: component name -> (sourceMin, sourceMax, finalDestination)
    private data class Binding(
        val sourceMin: BlockPos,
        val sourceMax: BlockPos,
        val destination: Vec3d
    )

    private val bindings = mutableMapOf<String, Binding>()
    private var timeline: AnimationTimeline? = null

    /**
     * Bind a component to world regions for clear/place lifecycle.
     *
     * @param componentName Name of the component to bind
     * @param sourceMin Min corner of the source region to clear
     * @param sourceMax Max corner of the source region to clear
     * @param destination World position to place blocks after animation
     */
    fun bind(componentName: String, sourceMin: BlockPos, sourceMax: BlockPos, destination: Vec3d): CompositeAnimationController {
        bindings[componentName] = Binding(sourceMin, sourceMax, destination)
        return this
    }

    /**
     * Auto-derive bindings from capture origins and last keyframes.
     *
     * Source region: derived from capture origin + bounds.
     * Destination: position of the last keyframe.
     */
    fun bindAll(): CompositeAnimationController {
        for (component in animation.components) {
            val capture = component.animation.capture
            val bounds = capture.bounds
            val origin = capture.origin

            val sourceMin = BlockPos(
                origin.x.toInt(),
                origin.y.toInt(),
                origin.z.toInt()
            )
            val sourceMax = BlockPos(
                origin.x.toInt() + bounds.max.x,
                origin.y.toInt() + bounds.max.y,
                origin.z.toInt() + bounds.max.z
            )

            val lastKeyframe = component.animation.keyframes.last()
            val destination = lastKeyframe.position

            bindings[component.name] = Binding(sourceMin, sourceMax, destination)
        }
        return this
    }

    /**
     * Start playback. Clears source regions (if bound) and begins animation.
     *
     * @param clearSource Whether to clear source regions before animating
     */
    fun play(clearSource: Boolean = true) {
        if (state == State.PLAYING) return

        // Create timeline with real entity display managers
        val tl = AnimationTimeline { capture ->
            FabricRegionDisplayManager(capture, level)
        }

        // Clear source regions before spawning displays
        if (clearSource) {
            for (component in animation.components) {
                val binding = bindings[component.name] ?: continue
                worldAccess.clear(binding.sourceMin, binding.sourceMax)
            }
        }

        // Add all component animations to the timeline
        for (component in animation.components) {
            tl.add(component.animation)
        }

        // Listen for completion
        tl.addListener(object : TimelineListener {
            override fun onAllComplete(timeline: AnimationTimeline) {
                onAnimationComplete()
            }
        })

        timeline = tl
        state = State.PLAYING
        listeners.forEach { it.onStart(this) }

        // Start all animators
        tl.play()
    }

    /**
     * Cancel the animation and destroy all display entities.
     */
    fun cancel() {
        val tl = timeline ?: return
        tl.stop()
        timeline = null
        state = State.IDLE
        listeners.forEach { it.onCancel(this) }
    }

    /**
     * Advance the animation by one tick.
     *
     * @return true if still playing, false if completed or idle
     */
    fun tick(): Boolean {
        val tl = timeline ?: return false
        return tl.tick()
    }

    fun addListener(listener: CompositeAnimationListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: CompositeAnimationListener) {
        listeners.remove(listener)
    }

    private fun onAnimationComplete() {
        // Place blocks at final positions for all bound components
        for (component in animation.components) {
            val binding = bindings[component.name] ?: continue
            worldAccess.place(component.animation.capture, binding.destination)
        }

        // Clean up display entities
        timeline?.stop()
        timeline = null

        state = State.COMPLETED
        listeners.forEach { it.onComplete(this) }
    }
}
