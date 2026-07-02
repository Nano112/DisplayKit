package io.schemat.displaykit.ui

import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.ui.elements.TabsElement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutMathTest {

    @Test
    fun textMetricsBasics() {
        assertEquals(1, TextMetrics.lineCount("hello"))
        assertEquals(3, TextMetrics.lineCount("a\nb\nc"))
        // Wider text -> wider block; multi-line uses the widest line
        assertTrue(TextMetrics.blockWidth("Modules", 0.35f) > TextMetrics.blockWidth("Sim", 0.35f))
        assertEquals(
            TextMetrics.blockWidth("Modules", 0.35f),
            TextMetrics.blockWidth("Modules\nab", 0.35f)
        )
        // Center correction is half the block height, downward
        val text = "line1\nline2"
        assertEquals(
            -TextMetrics.blockHeight(text, 0.4f) / 2f,
            TextMetrics.verticalCenterCorrection(text, 0.4f)
        )
    }

    @Test
    fun tabLayoutNeverOverlaps() {
        // The exact labels from the bug report: "Sim  ModulesStats"
        val labels = listOf("Sim", "Modules", "Stats")
        val gap = 0.05f
        val slots = TabsElement.computeLayout(labels, minWidth = 0.3f, gap = gap)

        assertEquals(labels.size, slots.size)

        // Every tab is wide enough for its label plus padding
        labels.forEachIndexed { i, label ->
            assertTrue(
                slots[i].width >= TextMetrics.blockWidth(label, TabsElement.DEFAULT_LABEL_SCALE) + 2 * TabsElement.DEFAULT_TAB_PADDING - 1e-4f,
                "tab $i too narrow for '$label'"
            )
        }

        // Adjacent tabs keep the requested gap between their edges
        for (i in 0 until slots.size - 1) {
            val rightEdge = slots[i].centerX + slots[i].width / 2
            val leftEdgeNext = slots[i + 1].centerX - slots[i + 1].width / 2
            assertEquals(gap, leftEdgeNext - rightEdge, 1e-4f)
        }

        // The row is centered on the element origin
        val min = slots.first().centerX - slots.first().width / 2
        val max = slots.last().centerX + slots.last().width / 2
        assertTrue(abs(min + max) < 1e-4f, "tab row not centered: [$min, $max]")
    }

    @Test
    fun tabLayoutRespectsMinWidth() {
        val slots = TabsElement.computeLayout(listOf("A", "B"), minWidth = 0.8f, gap = 0.1f)
        assertTrue(slots.all { it.width >= 0.8f })
    }
}
