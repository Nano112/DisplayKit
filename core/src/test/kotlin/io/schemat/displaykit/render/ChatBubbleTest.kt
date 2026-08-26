package io.schemat.displaykit.render

import kotlin.test.Test
import kotlin.test.assertTrue

class ChatBubbleTest {
    @Test
    fun `tail support is explicit rather than silently ignored`() {
        val tailed = ChatBubble.create("hello", tailPosition = ChatBubble.TailPosition.BOTTOM_LEFT)
        val notification = ChatBubble.create("hello", tailPosition = ChatBubble.TailPosition.NONE)

        assertTrue(tailed.plain().contains("bubble_tail"))
        assertTrue(!notification.plain().contains("bubble_tail"))
    }
}
