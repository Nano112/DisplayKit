package io.schemat.displaykit.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExclusiveSessionGroupTest {
    @Test
    fun `claim replaces previous resource and stale lease cannot evict replacement`() {
        val group = ExclusiveSessionGroup<String>()
        var firstClosed = false
        var secondClosed = false

        val first = group.claim("player") { firstClosed = true }
        val second = group.claim("player") { secondClosed = true }

        assertTrue(firstClosed)
        assertFalse(first.isActive)
        assertTrue(second.isActive)
        first.close()
        assertTrue(group.hasActive("player"))
        assertFalse(secondClosed)

        assertTrue(group.close("player"))
        assertTrue(secondClosed)
        assertFalse(group.hasActive("player"))
    }

    @Test
    fun `group close attempts every resource and aggregates failures`() {
        val group = ExclusiveSessionGroup<String>()
        var healthyClosed = false
        group.claim("broken") { error("broken close") }
        group.claim("healthy") { healthyClosed = true }

        val failure = kotlin.runCatching(group::close).exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(healthyClosed)
        assertFalse(group.hasActive("broken"))
        assertFalse(group.hasActive("healthy"))
    }
}
