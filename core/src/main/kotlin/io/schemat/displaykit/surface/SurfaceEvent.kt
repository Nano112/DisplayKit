package io.schemat.displaykit.surface

/** Which mouse button produced a click. */
enum class PointerButton { LEFT, RIGHT }

/** Whether a handler absorbed an event or wants it to keep bubbling. */
enum class EventResult { CONSUMED, PASS }

/**
 * An input event in absolute canvas pixels.
 *
 * Coordinates are always the surface's own canvas space, already resolved from
 * the world raycast by SurfacePicking — handlers never see world geometry.
 */
sealed interface SurfaceEvent {
    val x: Int
    val y: Int

    data class Click(
        override val x: Int,
        override val y: Int,
        val button: PointerButton
    ) : SurfaceEvent

    /** [delta] is in notches: negative is up/away, positive is down/toward. */
    data class Scroll(
        override val x: Int,
        override val y: Int,
        val delta: Int
    ) : SurfaceEvent

    data class PointerMove(override val x: Int, override val y: Int) : SurfaceEvent

    /** Not bubbled — describes one node's own state. */
    data class PointerEnter(override val x: Int, override val y: Int) : SurfaceEvent

    /** Not bubbled — describes one node's own state. */
    data class PointerExit(override val x: Int, override val y: Int) : SurfaceEvent
}
