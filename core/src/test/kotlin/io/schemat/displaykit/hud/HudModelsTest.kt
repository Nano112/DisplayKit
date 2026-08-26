package io.schemat.displaykit.hud

import io.schemat.displaykit.render.TextComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HudModelsTest {
    @Test
    fun `sidebar DSL preserves stable keys and rich text`() {
        val rich = TextComponent.of("Cells ") + TextComponent.of("12").copy(bold = true)
        val model = sidebar("island", "EXAMPLE") {
            line("island", "Island #4")
            line("cells", rich)
        }

        assertEquals(listOf("island", "cells"), model.lines.map { it.key })
        assertEquals("Cells 12", model.lines[1].content.plain())
    }

    @Test
    fun `sidebar rejects duplicate keys and native overflow`() {
        assertFailsWith<IllegalArgumentException> {
            sidebar("bad", "Bad") {
                line("same", "one")
                line("same", "two")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SidebarModel("bad", "Bad", List(16) { SidebarLine("line-$it", "$it") })
        }
    }

    @Test
    fun `progress model rejects invalid values`() {
        assertFailsWith<IllegalArgumentException> { ProgressBarModel("job", "Job", -0.01f) }
        assertFailsWith<IllegalArgumentException> { ProgressBarModel("job", "Job", Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ProgressBarModel("job", "Job", 1.01f) }
        assertEquals(0.5f, ProgressBarModel("job", "Job", 0.5f).progress)
    }
}
