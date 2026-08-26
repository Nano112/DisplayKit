package io.schemat.displaykit.action

import java.util.UUID

/** Renderer that originated an action event. */
enum class ActionSource {
    PROGRAMMATIC,
    INVENTORY_TOOLBAR,
    WORLD_SURFACE
}

/** Physical or logical gesture that originated an action event. */
enum class ActionTrigger {
    PROGRAMMATIC,
    PRIMARY_CLICK,
    SECONDARY_CLICK,
    SCROLL_FOCUS,
    POINTER_FOCUS
}

/**
 * Renderer-neutral input context passed to action callbacks.
 *
 * [actorId] is optional so pure tests and non-player automation do not need a
 * platform player object. Platform renderers should set it when available.
 */
data class ActionInteraction(
    val source: ActionSource,
    val trigger: ActionTrigger,
    val actorId: UUID? = null
) {
    companion object {
        @JvmField
        val PROGRAMMATIC = ActionInteraction(
            ActionSource.PROGRAMMATIC,
            ActionTrigger.PROGRAMMATIC
        )
    }
}
