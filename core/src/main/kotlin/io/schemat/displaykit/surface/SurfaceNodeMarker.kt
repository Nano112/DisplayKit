package io.schemat.displaykit.surface

/**
 * Capability markers, so input code can ask what a node can do without
 * depending on concrete layout classes.
 */
object SurfaceNodeMarker {
    /** Arms hotbar scroll capture while the pointer is inside. */
    interface Scrollable
}
