package io.schemat.displaykit.fabric.scheduler

import io.schemat.displaykit.platform.Scheduler
import io.schemat.displaykit.platform.TaskHandle
import io.schemat.displaykit.fabric.thread.ServerThreadDispatcher
import net.minecraft.server.MinecraftServer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class FabricScheduler(
    private val server: MinecraftServer
) : Scheduler {

    private val asyncExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "DisplayKit-Async").apply { isDaemon = true }
    }

    override fun runOnMainThread(task: Runnable) {
        ServerThreadDispatcher.dispatch(server, task)
    }

    override fun scheduleRepeating(delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle {
        val delayMs = delayTicks * 50
        val periodMs = periodTicks * 50
        val future = asyncExecutor.scheduleAtFixedRate({
            runOnMainThread(task)
        }, delayMs, periodMs, TimeUnit.MILLISECONDS)
        return FabricTaskHandle(future)
    }

    override fun scheduleDelayed(delayTicks: Long, task: Runnable): TaskHandle {
        val delayMs = delayTicks * 50
        val future = asyncExecutor.schedule({
            runOnMainThread(task)
        }, delayMs, TimeUnit.MILLISECONDS)
        return FabricTaskHandle(future)
    }

    fun shutdown() {
        asyncExecutor.shutdown()
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            asyncExecutor.shutdownNow()
        }
    }
}

class FabricTaskHandle(private val future: ScheduledFuture<*>) : TaskHandle {
    override fun cancel() {
        future.cancel(false)
    }

    override val isCancelled: Boolean
        get() = future.isCancelled
}
