package io.schemat.displaykit.theme

import io.schemat.displaykit.surface.BlockButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DisplayThemeTest {
    @Test
    fun `widget styles derive from one theme instance`() {
        val theme = DisplayTheme.VANILLA_DARK
        assertEquals(theme.colors.background, theme.windowStyle().backdrop)
        assertEquals(theme.materials.button, theme.actionMenuStyle().buttonBase)
        assertEquals(theme.colors.text, theme.propertySheetStyle().labelColor)
        assertEquals(BlockButton.HEIGHT, theme.controls.buttonHeight)
    }

    @Test
    fun `invalid geometry tokens fail at theme construction`() {
        assertFailsWith<IllegalArgumentException> { ThemeSpacing(xs = 8, sm = 4) }
        assertFailsWith<IllegalArgumentException> { ThemeDepth(content = 1, raised = 1) }
        assertFailsWith<IllegalArgumentException> { ThemeMotion(60) }
    }
}
