package io.schemat.displaykit.surface

/**
 * Turns a hotbar slot change into a scroll delta.
 *
 * Minecraft sends no scroll packet. The only scroll-driven signal that reaches
 * a server is the hotbar selection, so scrolling a surface means watching slot
 * changes and reading the shortest signed distance around the nine slots.
 *
 * Consequence: a spin of five or more notches in a single tick aliases onto
 * the short way round. That is inherent to mod-9 arithmetic, and is defined
 * behaviour rather than a defect.
 */
object ScrollWrap {
    const val SLOTS = 9
    const val MAX_NOTCH = SLOTS / 2   // 4

    fun delta(oldSlot: Int, newSlot: Int): Int =
        ((newSlot - oldSlot + MAX_NOTCH + SLOTS) % SLOTS) - MAX_NOTCH
}
