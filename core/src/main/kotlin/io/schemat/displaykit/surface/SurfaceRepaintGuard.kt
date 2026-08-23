package io.schemat.displaykit.surface

/**
 * Platform seam that lets a repaint resend the resource pack if it allocated
 * new glyphs.
 *
 * Pushing a glyph the client's pack does not define renders a missing-glyph
 * box, not an error: the server log stays clean, the client log stays clean,
 * and the only symptom is chrome that silently fails to draw. Two of three
 * tab frames disappeared this way, and nothing anywhere reported it.
 *
 * Opening a window already runs its paint under the pack ritual, so the open
 * path was safe. A HOVER repaint did not: [SurfaceHost] re-runs `paintTree`
 * whenever the pointer moves onto or off a node, and a hover-dependent widget
 * legitimately swaps in a sprite state its pre-warm may have missed. Every
 * window would have to remember to guard that path, and windows do not
 * remember -- the same argument that put the pack ritual itself in one place.
 *
 * Core cannot resend a pack, so the platform installs [guard] and core simply
 * routes its pushes through [guarded]. Uninstalled, this is a plain call, so
 * core keeps working with no platform at all (and in tests).
 */
object SurfaceRepaintGuard {

    /**
     * Runs a repaint, resending the pack if it grew. Installed by the platform.
     *
     * Volatile because paints are driven from the server thread and from
     * netty packet handlers.
     */
    @Volatile
    var guard: ((() -> Unit) -> Unit)? = null

    /** Run [block] under the installed guard, or directly if there is none. */
    fun guarded(block: () -> Unit) {
        val g = guard
        if (g == null) block() else g(block)
    }
}
