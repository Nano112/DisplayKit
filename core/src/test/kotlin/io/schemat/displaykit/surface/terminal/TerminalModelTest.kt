package io.schemat.displaykit.surface.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalModelTest {

    @Test
    fun appendPreservesOrder() {
        val model = TerminalModel(columns = 40)
        model.append("first")
        model.append("second")
        model.append("third")
        assertEquals(listOf("first", "second", "third"), model.lines())
    }

    @Test
    fun capacityEvictionDropsTheOldestRow() {
        val model = TerminalModel(columns = 40, capacity = 3)
        model.append("a")
        model.append("b")
        model.append("c")
        model.append("d")
        // "a" is the oldest and must be the one gone, not "d" (the newest).
        assertEquals(listOf("b", "c", "d"), model.lines())
    }

    @Test
    fun capacityEvictionAppliesAcrossManyAppends() {
        val model = TerminalModel(columns = 40, capacity = 5)
        for (i in 1..20) model.append("line$i")
        assertEquals(5, model.lines().size)
        assertEquals(listOf("line16", "line17", "line18", "line19", "line20"), model.lines())
    }

    @Test
    fun wrappingSplitsALongLineAtTheColumnCount() {
        val model = TerminalModel(columns = 5)
        model.append("abcdefghij") // 10 chars -> two 5-char rows
        assertEquals(listOf("abcde", "fghij"), model.lines())
    }

    @Test
    fun wrappingNeverProducesARowLongerThanTheColumnCount() {
        val columns = 7
        val model = TerminalModel(columns = columns)
        model.append("x".repeat(50))
        model.append("short")
        model.append("")
        for (row in model.lines()) {
            assertTrue(row.length <= columns, "row \"$row\" (${row.length} chars) exceeds $columns columns")
        }
    }

    @Test
    fun wrapHelperNeverExceedsColumnsEvenOnAnExactMultiple() {
        val rows = TerminalModel.wrap("abcdefgh", columns = 4)
        assertEquals(listOf("abcd", "efgh"), rows)
        rows.forEach { assertTrue(it.length <= 4) }
    }

    @Test
    fun clearEmptiesTheScrollback() {
        val model = TerminalModel(columns = 40)
        model.append("one")
        model.append("two")
        model.clear()
        assertEquals(emptyList(), model.lines())
    }

    @Test
    fun emptyLineStillProducesOneBlankRow() {
        val model = TerminalModel(columns = 10)
        model.append("")
        assertEquals(listOf(""), model.lines())
    }
}
