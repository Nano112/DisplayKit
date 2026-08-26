package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.SurfaceNode

/** One retained keyed row and the callbacks that keep it current. */
class KeyedRow<T>(
    val node: SurfaceNode,
    val update: (T) -> Unit = {},
    val dispose: () -> Unit = {}
)

data class KeyedReconcileResult(
    val added: Set<String>,
    val updated: Set<String>,
    val removed: Set<String>,
    val reordered: Boolean
) {
    val changed: Boolean get() = added.isNotEmpty() || updated.isNotEmpty() ||
        removed.isNotEmpty() || reordered
}

/**
 * Retained vertical collection reconciled by stable application keys.
 *
 * Existing row nodes survive insertion, removal, and reordering. Their node
 * IDs become surface reconciliation scopes, so unchanged rows retain virtual
 * entity IDs and updates send metadata rather than destroy/spawn packets.
 */
class KeyedColumn<T>(
    private val id: String,
    initialItems: List<T> = emptyList(),
    private val key: (T) -> String,
    gap: Int = 0,
    private val row: (rowId: String, item: T) -> KeyedRow<T>
) : AutoCloseable {
    val node = FlexNode(id, FlexDirection.COLUMN, gap = gap)

    private data class Entry<T>(
        val key: String,
        val row: KeyedRow<T>,
        var item: T
    )

    private var entries = LinkedHashMap<String, Entry<T>>()
    private var closed = false

    init {
        require(id.isNotBlank()) { "A keyed column id cannot be blank." }
        require(gap >= 0) { "A keyed column gap cannot be negative." }
        reconcile(initialItems)
    }

    fun keys(): List<String> = entries.keys.toList()

    fun reconcile(items: List<T>): KeyedReconcileResult {
        check(!closed) { "KeyedColumn '$id' is closed." }
        val keyed = items.map { item -> key(item) to item }
        keyed.forEach { (stableKey, _) ->
            require(stableKey.isNotBlank()) { "A keyed row key cannot be blank in '$id'." }
        }
        val duplicates = keyed.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
            "Keys must be unique in '$id' (duplicates: ${duplicates.joinToString()})."
        }

        val previousOrder = entries.keys.toList()
        val next = LinkedHashMap<String, Entry<T>>(keyed.size)
        val added = linkedSetOf<String>()
        val updated = linkedSetOf<String>()

        for ((stableKey, item) in keyed) {
            val existing = entries[stableKey]
            if (existing == null) {
                val rowId = "$id-row-$stableKey"
                val created = row(rowId, item)
                require(created.node.id == rowId) {
                    "Row factory for key '$stableKey' must use the supplied id '$rowId' " +
                        "(got '${created.node.id}')."
                }
                next[stableKey] = Entry(stableKey, created, item)
                added += stableKey
            } else {
                if (existing.item != item) {
                    existing.row.update(item)
                    existing.item = item
                    updated += stableKey
                }
                next[stableKey] = existing
            }
        }

        val removed = (entries.keys - next.keys).toSet()
        removed.forEach { entries.getValue(it).row.dispose() }

        entries = next
        node.clearChildren()
        entries.values.forEach { node.addChild(it.row.node) }

        return KeyedReconcileResult(
            added = added,
            updated = updated,
            removed = removed,
            reordered = previousOrder.isNotEmpty() && previousOrder != entries.keys.toList()
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        entries.values.toList().asReversed().forEach { it.row.dispose() }
        entries.clear()
        node.clearChildren()
    }
}
