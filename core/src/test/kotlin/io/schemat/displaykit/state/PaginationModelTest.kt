package io.schemat.displaykit.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaginationModelTest {
    @Test
    fun pagingClampsAsItemsAndPageSizeChange() {
        val scope = StateScope()
        val pager = PaginationModel(scope, initialItemCount = 13, initialPageSize = 5)

        assertEquals(3, pager.pageCount.value)
        assertTrue(pager.next())
        assertTrue(pager.next())
        assertFalse(pager.next())
        assertEquals(listOf(10, 11, 12), pager.slice((0 until 13).toList()).items)

        pager.update(itemCount = 3)
        assertEquals(0, pager.pageIndex.value)
        assertEquals(1, pager.pageCount.value)

        pager.update(itemCount = 9, pageSize = 4)
        pager.goTo(99)
        val slice = pager.slice((0 until 9).toList())
        assertEquals(2, slice.pageIndex)
        assertEquals(listOf(8), slice.items)
    }
}
