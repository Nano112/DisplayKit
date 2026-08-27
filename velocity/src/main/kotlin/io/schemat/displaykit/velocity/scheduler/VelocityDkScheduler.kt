package io.schemat.displaykit.velocity.scheduler

import io.schemat.displaykit.platform.Scheduler
import io.schemat.displaykit.platform.TaskHandle
import io.schemat.displaykit.velocity.thread.UiOwnerThread
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Core Scheduler mapped onto the module's UI owner thread.
 *
 * The owner thread's executor is already a scheduled single-thread pool, so
 * timers run directly on it: no trampoline, no cross-thread hop, and a
 * repeating task can never overlap itself.
 */
class VelocityDkScheduler(
    private val uiThread: UiOwnerThread,
) : Scheduler {

    override fun runOnMainThread(task: Runnable) {
        uiThread.dispatch(task)
    }

    override fun scheduleRepeating(delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle {
        val future = uiThread.executor.scheduleAtFixedRate(
            task, delayTicks * MILLIS_PER_TICK, periodTicks * MILLIS_PER_TICK, TimeUnit.MILLISECONDS
        )
        return VelocityTaskHandle(future)
    }

    override fun scheduleDelayed(delayTicks: Long, task: Runnable): TaskHandle {
        val future = uiThread.executor.schedule(
            task, delayTicks * MILLIS_PER_TICK, TimeUnit.MILLISECONDS
        )
        return VelocityTaskHandle(future)
    }

    private companion object {
        const val MILLIS_PER_TICK = 50L
    }
}

class VelocityTaskHandle(private val future: ScheduledFuture<*>) : TaskHandle {
    override fun cancel() {
        future.cancel(false)
    }

    override val isCancelled: Boolean
        get() = future.isCancelled
}
