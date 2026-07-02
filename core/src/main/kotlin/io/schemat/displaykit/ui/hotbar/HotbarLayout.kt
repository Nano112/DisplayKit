package io.schemat.displaykit.ui.hotbar

/**
 * Pure layout math for [VirtualHotbar]: pagination windowing and slot
 * positioning. Kept free of platform/entity types so it is unit-testable.
 *
 * Model: a hotbar shows at most [maxVisible] buttons. Chrome buttons (back /
 * prev / next) count against that budget:
 * - `back` occupies the far-left button whenever the menu stack is deeper
 *   than the root level.
 * - When the content does not fit the remaining budget, the strip is
 *   *paginated*: the first and last remaining buttons become prev / next and
 *   the content capacity shrinks accordingly. Prev/next are always both
 *   present on a paginated strip (disabled at the ends) so slot positions
 *   stay stable across pages.
 */
object HotbarLayout {

    /** One resolved page window over the content list. */
    data class Window(
        /** Index into the content list of the first visible content slot. */
        val startIndex: Int,
        /** Number of content slots visible on this page. */
        val count: Int,
        /** Clamped page index (0-based). */
        val page: Int,
        val pageCount: Int,
        val hasBack: Boolean,
        val paginated: Boolean
    ) {
        val hasPrev: Boolean get() = paginated && page > 0
        val hasNext: Boolean get() = paginated && page < pageCount - 1
        /** Total buttons rendered: content + chrome. */
        val visibleButtons: Int get() = count + (if (hasBack) 1 else 0) + (if (paginated) 2 else 0)
    }

    fun window(totalContent: Int, page: Int, hasBack: Boolean, maxVisible: Int = 9): Window {
        require(maxVisible >= 4) { "maxVisible must leave room for chrome + content (got $maxVisible)" }
        val backCost = if (hasBack) 1 else 0
        val singlePageCapacity = maxVisible - backCost

        if (totalContent <= singlePageCapacity) {
            return Window(
                startIndex = 0, count = totalContent,
                page = 0, pageCount = 1,
                hasBack = hasBack, paginated = false
            )
        }

        val capacity = maxVisible - backCost - 2 // prev + next
        val pageCount = (totalContent + capacity - 1) / capacity
        val clamped = page.coerceIn(0, pageCount - 1)
        val start = clamped * capacity
        return Window(
            startIndex = start,
            count = minOf(capacity, totalContent - start),
            page = clamped, pageCount = pageCount,
            hasBack = hasBack, paginated = true
        )
    }

    /**
     * Centered X offsets (in UI-local "right" units) for [count] buttons at
     * the given [pitch] (button size + gap). Symmetric around 0.
     */
    fun offsets(count: Int, pitch: Double): DoubleArray {
        if (count <= 0) return DoubleArray(0)
        val first = -(count - 1) * pitch / 2.0
        return DoubleArray(count) { first + it * pitch }
    }
}
