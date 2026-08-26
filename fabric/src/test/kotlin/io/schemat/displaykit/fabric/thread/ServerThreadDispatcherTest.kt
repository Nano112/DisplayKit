package io.schemat.displaykit.fabric.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerThreadDispatcherTest {
    @Test
    fun `owner thread runs immediately even during orderly shutdown`() {
        var calls = 0

        val accepted = dispatchSafely(
            isOwnerThread = { true },
            isAcceptingTasks = { false },
            enqueue = { error("must not enqueue") },
            task = Runnable { calls++ },
        )

        assertTrue(accepted)
        assertEquals(1, calls)
    }

    @Test
    fun `live off-thread caller queues work for the owner`() {
        var owner = false
        var queued: Runnable? = null
        var calls = 0

        val accepted = dispatchSafely(
            isOwnerThread = { owner },
            isAcceptingTasks = { true },
            enqueue = { queued = it },
            task = Runnable { calls++ },
        )
        owner = true
        queued!!.run()

        assertTrue(accepted)
        assertEquals(1, calls)
    }

    @Test
    fun `stopped server rejects off-thread work`() {
        var calls = 0

        val accepted = dispatchSafely(
            isOwnerThread = { false },
            isAcceptingTasks = { false },
            enqueue = { error("must not enqueue") },
            task = Runnable { calls++ },
        )

        assertFalse(accepted)
        assertEquals(0, calls)
    }

    @Test
    fun `inline enqueue during shutdown cannot leak work onto caller thread`() {
        var calls = 0

        val accepted = dispatchSafely(
            isOwnerThread = { false },
            isAcceptingTasks = { true },
            enqueue = Runnable::run,
            task = Runnable { calls++ },
        )

        assertTrue(accepted)
        assertEquals(0, calls)
    }
}
