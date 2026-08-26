package io.schemat.displaykit.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextMetricsTest {
    @Test
    fun ellipsizeNeverExceedsTheGrantedWidth() {
        val fitted = TextMetrics.ellipsize("A deliberately long public UI label", 72)
        assertTrue(fitted.endsWith("..."))
        assertTrue(TextMetrics.textWidthPx(fitted) <= 72)
        assertEquals("Short", TextMetrics.ellipsize("Short", 72))
        assertEquals("", TextMetrics.ellipsize("Long", 2))
        assertFailsWith<IllegalArgumentException> { TextMetrics.ellipsize("x", -1) }
    }
}
