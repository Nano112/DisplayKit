package io.schemat.displaykit.velocity.thread

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiOwnerThreadTest {

    private val uiThread = UiOwnerThread()

    @AfterTest
    fun tearDown() {
        uiThread.close()
    }

    @Test
    fun `work dispatched off-thread runs on the owner thread`() {
        val latch = CountDownLatch(1)
        val ranOnOwner = AtomicBoolean(false)

        assertTrue(uiThread.dispatch {
            ranOnOwner.set(uiThread.isOwnerThread())
            latch.countDown()
        })

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(ranOnOwner.get())
    }

    @Test
    fun `work dispatched on-thread runs inline`() {
        val order = AtomicInteger(0)
        val latch = CountDownLatch(1)

        uiThread.dispatch {
            // Now on the owner thread: a nested dispatch must run before this
            // task continues, proving inline execution
            uiThread.dispatch { order.compareAndSet(0, 1) }
            order.compareAndSet(1, 2)
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(2, order.get())
    }

    @Test
    fun `dispatch after close is refused`() {
        uiThread.close()
        assertFalse(uiThread.dispatch { })
    }

    @Test
    fun `dispatchSafely never runs off-thread work after shutdown`() {
        val ran = AtomicBoolean(false)

        val accepted = dispatchSafely(
            isOwnerThread = { false },
            isAcceptingTasks = { false },
            enqueue = { it.run() },
            task = { ran.set(true) },
        )

        assertFalse(accepted)
        assertFalse(ran.get())
    }

    @Test
    fun `dispatchSafely guards the inline shutdown race`() {
        val ran = AtomicBoolean(false)

        // An enqueue that runs inline while ownership never transfers: the
        // wrapper's re-check must keep the task from running off-thread
        val accepted = dispatchSafely(
            isOwnerThread = { false },
            isAcceptingTasks = { true },
            enqueue = { it.run() },
            task = { ran.set(true) },
        )

        assertTrue(accepted, "the dispatch was accepted even though the race swallowed it")
        assertFalse(ran.get(), "task must not run on the wrong thread")
    }
}
