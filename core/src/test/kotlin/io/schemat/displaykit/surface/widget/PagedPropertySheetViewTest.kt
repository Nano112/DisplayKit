package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.property.IntPropertyField
import io.schemat.displaykit.property.PropertySheetModel
import io.schemat.displaykit.state.StateScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagedPropertySheetViewTest {
    @Test
    fun `large property schemas are divided into bounded pages`() {
        val scope = StateScope()
        val fields = (0 until 10).map { index ->
            IntPropertyField("field-$index", "Field $index", 0, 10, get = { 0 }, set = {})
        }
        val view = PagedPropertySheetView(
            "properties",
            scope,
            PropertySheetModel(fields),
            pageSize = 4,
            isHovered = { false },
            onChanged = {},
        )

        assertEquals(3, view.pagination.pageCount.value)
        assertTrue(view.goToField("field-9"))
        assertEquals(2, view.pagination.pageIndex.value)
        assertFalse(view.goToField("missing"))
    }
}
