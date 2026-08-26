package io.schemat.displaykit.state

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates resources that are mutually exclusive per owner.
 *
 * Claiming a key closes the previous claim before the new caller proceeds.
 * The returned lease only unregisters its own claim, so closing a stale
 * resource can never evict the resource that replaced it.
 */
class ExclusiveSessionGroup<K> : AutoCloseable {
    internal class Entry(val token: Any, val closeResource: () -> Unit)

    private val entries = ConcurrentHashMap<K, Entry>()

    fun claim(key: K, closeResource: () -> Unit): Lease {
        val entry = Entry(Any(), closeResource)
        val previous = entries.put(key, entry)
        try {
            previous?.closeResource?.invoke()
        } catch (failure: Throwable) {
            entries.remove(key, entry)
            throw failure
        }
        return Lease(key, entry)
    }

    fun hasActive(key: K): Boolean = entries.containsKey(key)

    /** Close and unregister the current resource for [key], if one exists. */
    fun close(key: K): Boolean {
        val entry = entries.remove(key) ?: return false
        entry.closeResource()
        return true
    }

    override fun close() {
        var failure: Throwable? = null
        for ((key, entry) in entries.entries.toList()) {
            if (!entries.remove(key, entry)) continue
            try {
                entry.closeResource()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    inner class Lease internal constructor(
        private val key: K,
        private val entry: Entry
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        val isActive: Boolean
            get() = !released.get() && entries[key] === entry

        /** Unregister this claim without closing its resource. */
        override fun close() {
            if (released.compareAndSet(false, true)) entries.remove(key, entry)
        }
    }
}
