package io.schemat.displaykit.state

/** Read-only observable value owned by a [StateScope]. */
interface State<out T> {
    val value: T

    /** Subscribe to future distinct values. */
    fun subscribe(observer: StateObserver<@UnsafeVariance T>, emitCurrent: Boolean = false): AutoCloseable
}

fun interface StateObserver<T> {
    fun changed(value: T)
}

/** One coalesced scope flush. */
data class StateInvalidation(
    val changed: Set<State<*>>,
    val version: Long
)

fun interface StateInvalidationListener {
    fun invalidated(event: StateInvalidation)
}

internal class StateSubscription(private val onClose: () -> Unit) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}
