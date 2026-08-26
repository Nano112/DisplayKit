package io.schemat.displaykit.state

data class PageSlice<T>(
    val items: List<T>,
    val pageIndex: Int,
    val pageCount: Int,
    val firstItemIndex: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean
)

/** Shared clamped pagination state for lists and grids. */
class PaginationModel(
    private val scope: StateScope,
    initialItemCount: Int = 0,
    initialPageSize: Int = 10
) {
    private val mutableItemCount = scope.mutable(initialItemCount.also {
        require(it >= 0) { "itemCount cannot be negative." }
    })
    private val mutablePageSize = scope.mutable(initialPageSize.also {
        require(it > 0) { "pageSize must be positive." }
    })
    private val mutablePageIndex = scope.mutable(0)

    val itemCount: State<Int> get() = mutableItemCount
    val pageSize: State<Int> get() = mutablePageSize
    val pageIndex: State<Int> get() = mutablePageIndex
    val pageCount: DerivedState<Int> = scope.derived(mutableItemCount, mutablePageSize) {
        pageCount(mutableItemCount.value, mutablePageSize.value)
    }

    fun update(itemCount: Int = mutableItemCount.value, pageSize: Int = mutablePageSize.value) {
        require(itemCount >= 0) { "itemCount cannot be negative." }
        require(pageSize > 0) { "pageSize must be positive." }
        scope.batch {
            mutableItemCount.set(itemCount)
            mutablePageSize.set(pageSize)
            mutablePageIndex.set(
                mutablePageIndex.value.coerceIn(0, pageCount(itemCount, pageSize) - 1)
            )
        }
    }

    fun goTo(index: Int): Boolean = mutablePageIndex.set(
        index.coerceIn(0, pageCount.value - 1)
    )

    fun previous(): Boolean = goTo(mutablePageIndex.value - 1)
    fun next(): Boolean = goTo(mutablePageIndex.value + 1)

    fun <T> slice(items: List<T>): PageSlice<T> {
        if (items.size != mutableItemCount.value) update(itemCount = items.size)
        clamp()
        val index = mutablePageIndex.value
        val size = mutablePageSize.value
        val first = index * size
        val count = pageCount.value
        return PageSlice(
            items = items.drop(first).take(size),
            pageIndex = index,
            pageCount = count,
            firstItemIndex = first,
            hasPrevious = index > 0,
            hasNext = index + 1 < count
        )
    }

    private fun clamp() {
        mutablePageIndex.set(mutablePageIndex.value.coerceIn(0, pageCount.value - 1))
    }

    private companion object {
        fun pageCount(items: Int, size: Int): Int = maxOf(1, (items + size - 1) / size)
    }
}
