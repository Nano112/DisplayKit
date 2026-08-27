package io.schemat.displaykit.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The width table decides where text wraps and when it ellipsizes, so a
 * script measured at the wrong width is truncated long before it needs to be.
 */
class TextMetricsWidthTest {

    @Test
    fun `cyrillic measures like latin, not like unifont`() {
        // Drawn from the default font's nonlatin_european sheet
        assertEquals(6, TextMetrics.charWidthPx('Р'))
        assertEquals(6, TextMetrics.charWidthPx('я'))
        // Seven glyphs at the default advance, not seven unifont fallbacks
        assertEquals(7 * 6, TextMetrics.textWidthPx("Русский"))
        assertTrue(
            TextMetrics.textWidthPx("Русский") < 7 * TextMetrics.UNICODE_FALLBACK_WIDTH_PX,
            "Cyrillic must no longer be measured as a unifont fallback"
        )
    }

    @Test
    fun `greek and latin supplements measure like latin`() {
        assertEquals(6, TextMetrics.charWidthPx('Ω'))
        assertEquals(6, TextMetrics.charWidthPx('é'))
        assertEquals(6, TextMetrics.charWidthPx('ß'))
    }

    @Test
    fun `scripts that really do fall back stay over-estimated`() {
        // A row that measures shorter than it draws can overflow, so the
        // safe direction is kept for everything unifont actually serves
        assertEquals(TextMetrics.UNICODE_FALLBACK_WIDTH_PX, TextMetrics.charWidthPx('日'))
        assertEquals(TextMetrics.UNICODE_FALLBACK_WIDTH_PX, TextMetrics.charWidthPx('א'))
        assertEquals(TextMetrics.UNICODE_FALLBACK_WIDTH_PX, TextMetrics.charWidthPx('—'))
    }

    @Test
    fun `a russian description now fits the width it is drawn at`() {
        val russian = "Всё на сервере переводится на него"
        assertTrue(
            TextMetrics.textWidthPx(russian) <= 214,
            "expected the line to fit a 214px panel, measured ${TextMetrics.textWidthPx(russian)}"
        )
    }
}
