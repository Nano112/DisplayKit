package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.state.PaginationModel
import io.schemat.displaykit.state.StateScope
import io.schemat.displaykit.surface.layout.CrossAxis
import io.schemat.displaykit.surface.PointerButton
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode

data class PagedDataListStyle(
    val gap: Int = 3,
    val controlsVisibleForSinglePage: Boolean = false
) {
    init { require(gap >= 0) { "Paged-list gap cannot be negative." } }
}

/** A keyed data table with shared, clamped paging controls and page status. */
class PagedDataListView<T>(
    id: String,
    private val scope: StateScope,
    columns: List<DataColumn<T>>,
    private val entries: () -> List<DataEntry<T>>,
    pageSize: Int,
    dataStyle: DataListStyle,
    selectedKey: () -> String? = { null },
    isHovered: (String) -> Boolean,
    onSelected: ((DataEntry<T>) -> Unit)? = null,
    onActivated: ((DataEntry<T>, PointerButton) -> Unit)? = null,
    val style: PagedDataListStyle = PagedDataListStyle()
) : AutoCloseable {
    private val revision = scope.mutable(0L)
    val pagination = PaginationModel(scope, entries().size, pageSize)
    val list = DataList(
        id = "$id-list",
        columns = columns,
        initialEntries = pagination.slice(entries()).items,
        style = dataStyle,
        selectedKey = selectedKey,
        isHovered = isHovered,
        onSelected = onSelected,
        onActivated = onActivated
    )
    val node = FlexNode(id, FlexDirection.COLUMN, crossAxis = CrossAxis.CENTER, gap = style.gap)

    init {
        require(pageSize > 0) { "Paged-list page size must be positive." }
        node.addChild(list.node)
        node.addChild(
            PagerView(
                id = "$id-pager",
                pageIndex = { pagination.pageIndex.value },
                pageCount = { pagination.pageCount.value },
                visible = ::controlsVisible,
                isHovered = isHovered,
                onPrevious = {
                    scope.batch {
                        pagination.previous()
                        reconcileNow()
                    }
                },
                onNext = {
                    scope.batch {
                        pagination.next()
                        reconcileNow()
                    }
                },
                style = PagerStyle(width = dataStyle.width)
            ).node
        )
    }

    /** Re-read application entries, clamp the page and reconcile stable rows. */
    fun reconcile(): KeyedReconcileResult = scope.batch {
        pagination.update(itemCount = entries().size)
        reconcileNow()
    }

    override fun close() = list.close()

    private fun reconcileNow(): KeyedReconcileResult {
        val result = list.reconcile(pagination.slice(entries()).items)
        if (result.changed) revision.set(revision.value + 1)
        return result
    }

    private fun controlsVisible(): Boolean =
        style.controlsVisibleForSinglePage || pagination.pageCount.value > 1
}
