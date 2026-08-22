package io.schemat.displaykit.surface.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCommandsTest {

    @Test
    fun unknownInputEchoesOnly() {
        val model = TerminalModel(columns = 40)
        TerminalCommands.execute(model, "whatever")
        assertEquals(listOf("> whatever"), model.lines())
    }

    @Test
    fun clearWipesTheScrollbackIncludingItsOwnEcho() {
        val model = TerminalModel(columns = 40)
        model.append("earlier output")
        TerminalCommands.execute(model, "clear")
        assertEquals(emptyList(), model.lines())
    }

    @Test
    fun helpListsTheKnownCommandsAfterEchoing() {
        val model = TerminalModel(columns = 40)
        TerminalCommands.execute(model, "help")
        assertEquals(2, model.lines().size)
        assertEquals("> help", model.lines()[0])
    }

    @Test
    fun commandMatchingIsCaseAndWhitespaceInsensitive() {
        val model = TerminalModel(columns = 40)
        TerminalCommands.execute(model, "  CLEAR  ")
        assertEquals(emptyList(), model.lines())
    }
}
