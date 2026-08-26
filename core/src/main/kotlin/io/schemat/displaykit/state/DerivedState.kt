package io.schemat.displaykit.state

/** Read-only state computed from one or more observable dependencies. */
class DerivedState<T> internal constructor(
    scope: StateScope,
    dependencies: List<State<*>>,
    private val compute: () -> T
) : State<T>, AutoCloseable {
    private val backing = scope.mutable(compute())
    private val subscriptions = dependencies.distinct().map { dependency ->
        @Suppress("UNCHECKED_CAST")
        (dependency as State<Any?>).subscribe(StateObserver { recompute() })
    }
    private var closed = false

    override val value: T get() = backing.value

    override fun subscribe(observer: StateObserver<T>, emitCurrent: Boolean): AutoCloseable =
        backing.subscribe(observer, emitCurrent)

    private fun recompute() {
        if (!closed) backing.set(compute())
    }

    override fun close() {
        if (closed) return
        closed = true
        subscriptions.asReversed().forEach { it.close() }
    }
}
