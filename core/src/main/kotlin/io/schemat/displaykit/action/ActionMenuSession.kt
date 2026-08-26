package io.schemat.displaykit.action

/** Why an [ActionMenuSession] snapshot changed. */
enum class ActionMenuChange {
    PUSH,
    POP,
    POP_TO_ROOT,
    REPLACE,
    PAGE,
    FOCUS,
    CLOSE
}

enum class ActionInvocationResult {
    INVOKED,
    NOT_FOUND,
    HIDDEN,
    DISABLED,
    BUSY,
    CLOSED
}

data class ActionMenuSnapshot(
    val currentPage: ActionPage,
    val depth: Int,
    val pageIndex: Int,
    val focusedActionId: String?,
    val closed: Boolean,
    val version: Long,
    val change: ActionMenuChange?
)

data class ActionPageWindow(
    val actions: List<ActionSpec>,
    val pageIndex: Int,
    val pageCount: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean
)

fun interface ActionMenuListener {
    fun changed(snapshot: ActionMenuSnapshot)
}

/**
 * Pure renderer-independent navigation and action state.
 *
 * The session is intentionally synchronous and thread-confined. Minecraft
 * integrations should mutate it on the server thread, while unit tests and
 * non-Minecraft renderers can use it without platform dependencies.
 */
class ActionMenuSession(root: ActionPage) : AutoCloseable {

    private data class Level(var page: ActionPage, var pageIndex: Int = 0)

    private val stack = ArrayDeque<Level>().apply { addLast(Level(root)) }
    // Identity list: callback SAMs can capture mutable receivers whose
    // hashCode changes, so hash-based removal can leak subscriptions.
    private val listeners = mutableListOf<ActionMenuListener>()
    private var focusedId: String? = null
    private var closed = false
    private var version = 0L

    val isClosed: Boolean get() = closed
    val depth: Int get() = stack.size
    val currentPage: ActionPage get() = stack.last().page
    val currentPageId: String get() = currentPage.id
    val pageIndex: Int get() = stack.last().pageIndex
    val focusedActionId: String? get() = focusedId

    fun snapshot(change: ActionMenuChange? = null): ActionMenuSnapshot = ActionMenuSnapshot(
        currentPage = currentPage,
        depth = depth,
        pageIndex = pageIndex,
        focusedActionId = focusedId,
        closed = closed,
        version = version,
        change = change
    )

    /** Subscribe to future changes. Set [emitCurrent] for an initial snapshot. */
    @JvmOverloads
    fun subscribe(listener: ActionMenuListener, emitCurrent: Boolean = false): AutoCloseable {
        listeners += listener
        if (emitCurrent) listener.changed(snapshot())
        return AutoCloseable {
            listeners.indexOfFirst { it === listener }
                .takeIf { it >= 0 }
                ?.let(listeners::removeAt)
        }
    }

    fun push(page: ActionPage): Boolean {
        if (closed) return false
        stack.addLast(Level(page))
        focusedId = null
        changed(ActionMenuChange.PUSH)
        return true
    }

    /** Pop one level; popping the root closes the session. */
    fun pop(): Boolean {
        if (closed) return false
        if (stack.size == 1) {
            close()
            return true
        }
        stack.removeLast()
        focusedId = null
        changed(ActionMenuChange.POP)
        return true
    }

    fun popToRoot(): Boolean {
        if (closed) return false
        val changedDepth = stack.size > 1
        while (stack.size > 1) stack.removeLast()
        focusedId = null
        if (changedDepth) changed(ActionMenuChange.POP_TO_ROOT)
        return changedDepth
    }

    /** Replace the current level, preserving page and focus when still valid. */
    @JvmOverloads
    fun replaceCurrent(page: ActionPage, preserveFocus: Boolean = true): Boolean {
        if (closed) return false
        val level = stack.last()
        val previousFocus = focusedId
        level.page = page
        focusedId = if (
            preserveFocus && previousFocus != null &&
            page.actions.any { it.id == previousFocus && it.visible }
        ) previousFocus else null
        changed(ActionMenuChange.REPLACE)
        return true
    }

    /** Current visible page slice for a renderer's content capacity. */
    fun window(pageSize: Int): ActionPageWindow {
        require(pageSize > 0) { "pageSize must be positive (got $pageSize)." }
        val visible = currentPage.visibleActions()
        val pageCount = maxOf(1, (visible.size + pageSize - 1) / pageSize)
        val level = stack.last()
        level.pageIndex = level.pageIndex.coerceIn(0, pageCount - 1)
        val actions = visible.drop(level.pageIndex * pageSize).take(pageSize)
        return ActionPageWindow(
            actions = actions,
            pageIndex = level.pageIndex,
            pageCount = pageCount,
            hasPrevious = level.pageIndex > 0,
            hasNext = level.pageIndex + 1 < pageCount
        )
    }

    fun previousPage(pageSize: Int): Boolean = changePage(-1, pageSize)
    fun nextPage(pageSize: Int): Boolean = changePage(1, pageSize)

    private fun changePage(delta: Int, pageSize: Int): Boolean {
        if (closed) return false
        val view = window(pageSize)
        val target = (view.pageIndex + delta).coerceIn(0, view.pageCount - 1)
        if (target == view.pageIndex) return false
        stack.last().pageIndex = target
        focusedId = null
        changed(ActionMenuChange.PAGE)
        return true
    }

    /** Focus by stable action ID. Focus callbacks run once per actual change. */
    @JvmOverloads
    fun focus(
        actionId: String?,
        interaction: ActionInteraction = ActionInteraction.PROGRAMMATIC
    ): Boolean {
        if (closed) return false
        val action = actionId?.let { id -> currentPage.actions.firstOrNull { it.id == id && it.visible } }
        val resolved = action?.id
        if (resolved == focusedId) return false
        focusedId = resolved
        changed(ActionMenuChange.FOCUS)
        action?.onFocus?.handle(ActionContext(this, action, interaction))
        return true
    }

    @JvmOverloads
    fun invoke(
        actionId: String,
        interaction: ActionInteraction = ActionInteraction.PROGRAMMATIC
    ): ActionInvocationResult {
        if (closed) return ActionInvocationResult.CLOSED
        val action = currentPage.actions.firstOrNull { it.id == actionId }
            ?: return ActionInvocationResult.NOT_FOUND
        if (!action.visible) return ActionInvocationResult.HIDDEN
        if (action.busy) return ActionInvocationResult.BUSY
        if (!action.enabled) return ActionInvocationResult.DISABLED
        action.onInvoke?.handle(ActionContext(this, action, interaction))
        return ActionInvocationResult.INVOKED
    }

    override fun close() {
        if (closed) return
        closed = true
        focusedId = null
        changed(ActionMenuChange.CLOSE)
    }

    private fun changed(reason: ActionMenuChange) {
        version++
        val event = snapshot(reason)
        listeners.toList().forEach { it.changed(event) }
    }
}
