package io.schemat.displaykit.showcase

import io.schemat.displaykit.state.ExclusiveSessionGroup
import java.util.UUID

/** Prevents acceptance windows from obscuring one another during live QA. */
internal object ShowcaseWindowGroup {
    private val windows = ExclusiveSessionGroup<UUID>()

    fun claim(playerId: UUID, closeWindow: () -> Unit): AutoCloseable =
        windows.claim(playerId, closeWindow)
}
