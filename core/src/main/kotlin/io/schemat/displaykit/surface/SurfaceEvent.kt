package io.schemat.displaykit.surface

/** Filled out in Task 4. */
sealed interface SurfaceEvent {
    val x: Int
    val y: Int
}

enum class EventResult { CONSUMED, PASS }
