package io.schemat.displaykit.state

/** Mutable observable value created by [StateScope.mutable]. */
class MutableState<T> internal constructor(
    private val scope: StateScope,
    initial: T,
    private val equality: (T, T) -> Boolean
) : State<T> {
    @Volatile
    private var current: T = initial
    // Identity list, not a hash set: a SAM may capture a mutable receiver
    // whose hashCode changes after notification, making hash-based removal
    // silently fail and leak the subscription.
    private val observers = mutableListOf<StateObserver<T>>()
    private var closed = false

    override val value: T get() = current

    /** Set a distinct value. Returns true only when it changed. */
    fun set(value: T): Boolean {
        scope.checkAccess()
        scope.checkOpen()
        check(!closed) { "State is closed." }
        if (equality(current, value)) return false
        current = value
        scope.changed(this)
        return true
    }

    fun update(transform: (T) -> T): Boolean = set(transform(current))

    override fun subscribe(observer: StateObserver<T>, emitCurrent: Boolean): AutoCloseable {
        scope.checkAccess()
        scope.checkOpen()
        check(!closed) { "State is closed." }
        observers.add(observer)
        if (emitCurrent) observer.changed(current)
        return StateSubscription {
            scope.checkAccess()
            observers.indexOfFirst { it === observer }
                .takeIf { it >= 0 }
                ?.let(observers::removeAt)
        }
    }

    internal fun publish(onFailure: (Throwable) -> Unit) {
        observers.toList().forEach { observer ->
            runCatching { observer.changed(current) }.onFailure(onFailure)
        }
    }

    internal fun closeFromScope() {
        closed = true
        observers.clear()
    }
}
