package io.schemat.displaykit.ui.hotbar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotbarLayoutTest {

    @Test
    fun singlePageNoChrome() {
        val w = HotbarLayout.window(totalContent = 5, page = 0, hasBack = false, maxVisible = 9)
        assertFalse(w.paginated)
        assertEquals(0, w.startIndex)
        assertEquals(5, w.count)
        assertEquals(1, w.pageCount)
        assertEquals(5, w.visibleButtons)
        assertFalse(w.hasPrev)
        assertFalse(w.hasNext)
    }

    @Test
    fun exactFitDoesNotPaginate() {
        val w = HotbarLayout.window(totalContent = 9, page = 0, hasBack = false, maxVisible = 9)
        assertFalse(w.paginated)
        assertEquals(9, w.count)
    }

    @Test
    fun backSlotShrinksSinglePageCapacity() {
        // 9 items + back = 10 buttons -> must paginate at maxVisible 9
        val w = HotbarLayout.window(totalContent = 9, page = 0, hasBack = true, maxVisible = 9)
        assertTrue(w.paginated)
        // capacity = 9 - 1 (back) - 2 (nav) = 6
        assertEquals(6, w.count)
        assertEquals(2, w.pageCount)
        assertEquals(9, w.visibleButtons) // 6 content + back + prev + next
    }

    @Test
    fun paginationWindowsAndFlags() {
        // 20 items, no back: capacity = 9 - 2 = 7 -> pages of 7,7,6
        val p0 = HotbarLayout.window(20, 0, hasBack = false)
        assertEquals(0, p0.startIndex); assertEquals(7, p0.count)
        assertFalse(p0.hasPrev); assertTrue(p0.hasNext)

        val p2 = HotbarLayout.window(20, 2, hasBack = false)
        assertEquals(14, p2.startIndex); assertEquals(6, p2.count)
        assertTrue(p2.hasPrev); assertFalse(p2.hasNext)
        assertEquals(3, p2.pageCount)
    }

    @Test
    fun pageIndexClamps() {
        val under = HotbarLayout.window(20, -3, hasBack = false)
        assertEquals(0, under.page)
        val over = HotbarLayout.window(20, 99, hasBack = false)
        assertEquals(over.pageCount - 1, over.page)
    }

    @Test
    fun offsetsAreCenteredAndSymmetric() {
        val xs = HotbarLayout.offsets(4, pitch = 0.3)
        assertEquals(4, xs.size)
        // symmetric around zero
        assertEquals(-xs[3], xs[0], 1e-9)
        assertEquals(-xs[2], xs[1], 1e-9)
        // pitch respected
        assertEquals(0.3, xs[1] - xs[0], 1e-9)

        val odd = HotbarLayout.offsets(3, pitch = 0.3)
        assertEquals(0.0, odd[1], 1e-9) // middle slot dead-center

        assertEquals(0, HotbarLayout.offsets(0, 0.3).size)
    }
}
