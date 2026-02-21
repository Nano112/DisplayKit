package io.schemat.displaykit.platform

interface Scheduler {
    fun runOnMainThread(task: Runnable)
    fun scheduleRepeating(delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle
    fun scheduleDelayed(delayTicks: Long, task: Runnable): TaskHandle
}

interface TaskHandle {
    fun cancel()
    val isCancelled: Boolean
}
