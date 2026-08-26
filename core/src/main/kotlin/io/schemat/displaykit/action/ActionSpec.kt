package io.schemat.displaykit.action

/** Context supplied to action focus and invocation handlers. */
data class ActionContext(
    val session: ActionMenuSession,
    val action: ActionSpec,
    val interaction: ActionInteraction = ActionInteraction.PROGRAMMATIC
)

fun interface ActionHandler {
    fun handle(context: ActionContext)
}

/**
 * One semantic action, independent of how or where it is rendered.
 *
 * [id] is stable application identity. Renderers may change slot, row, page,
 * or medium without changing what an action means. [visible], [enabled], and
 * [busy] are immutable snapshot values; replace the containing [ActionPage]
 * when application state changes and [ActionMenuSession.replaceCurrent]
 * preserves focus by ID.
 */
data class ActionSpec(
    val id: String,
    val label: String,
    val icon: ActionIcon = ActionIcon.Default,
    val description: String? = null,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val busy: Boolean = false,
    val onFocus: ActionHandler? = null,
    val onInvoke: ActionHandler? = null
) {
    init {
        require(id.isNotBlank()) { "An action id cannot be blank." }
    }

    val invokable: Boolean get() = visible && enabled && !busy

    companion object {
        /** Action that pushes a freshly-built child page when invoked. */
        @JvmStatic
        @JvmOverloads
        fun submenu(
            id: String,
            label: String,
            icon: ActionIcon = ActionIcon.Default,
            description: String? = null,
            enabled: Boolean = true,
            page: () -> ActionPage
        ): ActionSpec = ActionSpec(
            id = id,
            label = label,
            icon = icon,
            description = description,
            enabled = enabled,
            onInvoke = ActionHandler { it.session.push(page()) }
        )
    }
}

/** A navigation level containing uniquely-keyed actions. */
class ActionPage(
    val id: String,
    actions: List<ActionSpec>,
    val title: String? = null,
    val groups: List<ActionGroup> = emptyList()
) {
    val actions: List<ActionSpec> = actions.toList()

    init {
        require(id.isNotBlank()) { "An action page id cannot be blank." }
        val duplicates = this.actions.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
            "Action ids must be unique within page '$id' (duplicates: ${duplicates.joinToString()})."
        }
    }

    fun visibleActions(): List<ActionSpec> = actions.filter { it.visible }

    companion object {
        /** Build a page whose visual grouping metadata shares one action list. */
        @JvmStatic
        @JvmOverloads
        fun grouped(
            id: String,
            groups: List<ActionGroup>,
            title: String? = null
        ): ActionPage = ActionPage(
            id = id,
            actions = groups.flatMap { it.actions },
            title = title,
            groups = groups.toList()
        )
    }
}

/** Optional semantic grouping for renderers that can display sections. */
data class ActionGroup(
    val id: String,
    val actions: List<ActionSpec>,
    val label: String? = null
) {
    init {
        require(id.isNotBlank()) { "An action group id cannot be blank." }
        val duplicates = actions.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
            "Action ids must be unique within group '$id' (duplicates: ${duplicates.joinToString()})."
        }
    }
}
