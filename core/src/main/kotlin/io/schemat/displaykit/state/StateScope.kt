package io.schemat.displaykit.state

/**
 * Lifecycle and batching boundary for UI state.
 *
 * Mutation and subscription are thread-confined to the thread that creates
 * the scope. Values are safely readable from other threads, but asynchronous
 * work must marshal mutations back to the owner thread. A batch publishes
 * each changed state once and emits one scope invalidation after the final
 * values settle, including re-entrant derived changes.
 */
class StateScope : AutoCloseable {
    private val ownerThread = Thread.currentThread()
    private val states = LinkedHashSet<MutableState<*>>()
    private val resources = LinkedHashSet<AutoCloseable>()
    private val pending = LinkedHashSet<MutableState<*>>()
    private val invalidationListeners = mutableListOf<StateInvalidationListener>()
    private var batchDepth = 0
    private var flushing = false
    private var closed = false
    private var version = 0L

    val isClosed: Boolean get() = closed
    val currentVersion: Long get() = version

    @JvmOverloads
    fun <T> mutable(initial: T, equality: (T, T) -> Boolean = { a, b -> a == b }): MutableState<T> {
        checkAccess()
        checkOpen()
        return MutableState(this, initial, equality).also { states += it }
    }

    /** Derived value recomputed when any dependency publishes a change. */
    fun <T> derived(vararg dependencies: State<*>, compute: () -> T): DerivedState<T> {
        checkAccess()
        checkOpen()
        return DerivedState(this, dependencies.toList(), compute).also { resources += it }
    }

    /**
     * Attach any lifecycle resource to this scope. Resources close in reverse
     * registration order before state is discarded, so timers, subscriptions
     * and async controllers cannot outlive their UI.
     */
    fun <T : AutoCloseable> own(resource: T): T {
        checkAccess()
        checkOpen()
        resources += resource
        return resource
    }

    /** Run mutations atomically from observers' perspective. */
    fun <T> batch(block: () -> T): T {
        checkAccess()
        checkOpen()
        batchDepth++
        var result: Result<T>? = null
        try {
            result = runCatching(block)
        } finally {
            batchDepth--
            if (batchDepth == 0) {
                val flushFailure = runCatching { flush() }.exceptionOrNull()
                if (flushFailure != null) {
                    val blockFailure = result?.exceptionOrNull()
                    if (blockFailure != null) blockFailure.addSuppressed(flushFailure)
                    else throw flushFailure
                }
            }
        }
        return result!!.getOrThrow()
    }

    @JvmOverloads
    fun onInvalidated(
        listener: StateInvalidationListener,
        emitCurrent: Boolean = false
    ): AutoCloseable {
        checkAccess()
        checkOpen()
        invalidationListeners.add(listener)
        if (emitCurrent) listener.invalidated(StateInvalidation(emptySet(), version))
        return subscription {
            invalidationListeners.indexOfFirst { it === listener }
                .takeIf { it >= 0 }
                ?.let(invalidationListeners::removeAt)
        }
    }

    internal fun changed(state: MutableState<*>) {
        checkAccess()
        checkOpen()
        pending += state
        if (batchDepth == 0 && !flushing) flush()
    }

    internal fun checkAccess() {
        check(Thread.currentThread() === ownerThread) {
            "StateScope mutations and subscriptions must run on its owner thread " +
                "(${ownerThread.name}); current thread is ${Thread.currentThread().name}."
        }
    }

    internal fun checkOpen() {
        check(!closed) { "StateScope is closed." }
    }

    override fun close() {
        checkAccess()
        if (closed) return
        closed = true
        resources.toList().asReversed().forEach { runCatching { it.close() } }
        resources.clear()
        states.forEach { it.closeFromScope() }
        states.clear()
        pending.clear()
        invalidationListeners.clear()
    }

    private fun flush() {
        if (flushing || pending.isEmpty()) return
        flushing = true
        val allChanged = LinkedHashSet<State<*>>()
        var firstFailure: Throwable? = null
        try {
            while (pending.isNotEmpty()) {
                val wave = pending.toList()
                pending.clear()
                wave.forEach { state ->
                    allChanged += state
                    state.publish { error -> if (firstFailure == null) firstFailure = error }
                }
            }
            version++
            val event = StateInvalidation(allChanged, version)
            invalidationListeners.toList().forEach { listener ->
                runCatching { listener.invalidated(event) }
                    .onFailure { if (firstFailure == null) firstFailure = it }
            }
        } finally {
            flushing = false
        }
        firstFailure?.let { throw it }
    }

    private fun subscription(close: () -> Unit): AutoCloseable {
        return StateSubscription {
            checkAccess()
            close()
        }
    }
}
