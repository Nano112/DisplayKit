package io.schemat.displaykit.hud

import io.schemat.displaykit.render.TextComponent

/** A renderer for a platform-neutral HUD model. */
fun interface HudRenderer<in M> : AutoCloseable {
    fun render(model: M)

    override fun close() = Unit
}

/** One stable, independently diffable row in a sidebar. */
data class SidebarLine(
    val key: String,
    val content: TextComponent,
) {
    init {
        require(key.isNotBlank()) { "Sidebar line keys must not be blank" }
    }

    constructor(key: String, text: String) : this(key, TextComponent.of(text))
}

/**
 * Renderer-neutral sidebar state.
 *
 * Line keys are identities, not positions. Keeping them stable lets native
 * renderers update text and ordering without clearing the whole sidebar.
 */
data class SidebarModel(
    val id: String,
    val title: TextComponent,
    val lines: List<SidebarLine>,
    val visible: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "Sidebar id must not be blank" }
        require(lines.size <= MAX_LINES) {
            "A sidebar supports at most $MAX_LINES lines, got ${lines.size}"
        }
        val duplicateKeys = lines.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
        require(duplicateKeys.isEmpty()) { "Sidebar line keys must be unique: $duplicateKeys" }
    }

    constructor(
        id: String,
        title: String,
        lines: List<SidebarLine>,
        visible: Boolean = true,
    ) : this(id, TextComponent.of(title), lines, visible)

    companion object {
        /** Vanilla clients display at most fifteen scoreboard rows. */
        const val MAX_LINES = 15
    }
}

/** Build a sidebar with stable keyed lines and validation at the API boundary. */
inline fun sidebar(
    id: String,
    title: TextComponent,
    visible: Boolean = true,
    build: SidebarBuilder.() -> Unit,
): SidebarModel = SidebarBuilder().apply(build).toModel(id, title, visible)

inline fun sidebar(
    id: String,
    title: String,
    visible: Boolean = true,
    build: SidebarBuilder.() -> Unit,
): SidebarModel = sidebar(id, TextComponent.of(title), visible, build)

class SidebarBuilder {
    private val lines = mutableListOf<SidebarLine>()

    fun line(key: String, text: String) {
        lines += SidebarLine(key, text)
    }

    fun line(key: String, content: TextComponent) {
        lines += SidebarLine(key, content)
    }

    @PublishedApi
    internal fun toModel(id: String, title: TextComponent, visible: Boolean): SidebarModel =
        SidebarModel(id, title, lines.toList(), visible)
}

enum class ProgressBarColor { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }

enum class ProgressBarOverlay { PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20 }

/** Renderer-neutral state for a native progress/boss bar. */
data class ProgressBarModel(
    val id: String,
    val title: TextComponent,
    val progress: Float,
    val color: ProgressBarColor = ProgressBarColor.WHITE,
    val overlay: ProgressBarOverlay = ProgressBarOverlay.PROGRESS,
    val visible: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "Progress bar id must not be blank" }
        require(progress.isFinite() && progress in 0f..1f) {
            "Progress must be finite and between 0 and 1, got $progress"
        }
    }

    constructor(
        id: String,
        title: String,
        progress: Float,
        color: ProgressBarColor = ProgressBarColor.WHITE,
        overlay: ProgressBarOverlay = ProgressBarOverlay.PROGRESS,
        visible: Boolean = true,
    ) : this(id, TextComponent.of(title), progress, color, overlay, visible)
}
