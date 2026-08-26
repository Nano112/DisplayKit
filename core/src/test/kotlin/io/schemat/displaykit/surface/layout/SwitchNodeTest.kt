package io.schemat.displaykit.surface.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwitchNodeTest {
    @Test
    fun `pages can be reconciled without replacing the switch`() {
        var selected = 1
        val switch = SwitchNode("switch") { selected }
        val one = WidgetNode("one")
        val two = WidgetNode("two")

        switch.addPage(1, one)
        switch.putPage(2, two)
        assertEquals(setOf(1, 2), switch.pageKeys())
        assertEquals(listOf(one), switch.visibleChildren())

        selected = 2
        assertEquals(listOf(two), switch.visibleChildren())
        assertTrue(switch.removePage(2))
        assertFalse(switch.removePage(2))
        assertNull(switch.visibleChildren().singleOrNull())
    }
}
