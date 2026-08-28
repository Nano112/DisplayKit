package io.schemat.displaykit.velocity.thread

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The thread every DisplayKit mutation runs on.
 *
 * Velocity has no main thread, so this module supplies one: a single
 * dedicated executor plays the role Minecraft's server thread plays on
 * Fabric. Surfaces, sessions and the animation ticker are all confined to
 * it, which is what lets core keep its single-owner-thread invariants
 * without knowing the platform.
 *
 * The dispatch semantics mirror fabric's ServerThreadDispatcher: work is run
 * inline when already on the owner thread, rejected once shutdown began, and
 * the queued wrapper re-checks ownership so an executor that runs tasks
 * inline during a shutdown race can never turn into an off-thread mutation.
 */
class UiOwnerThread : AutoCloseable {

    @Volatile
    private var ownerThread: Thread? = null

    private val acceptingTasks = AtomicBoolean(true)

    val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "DisplayKit-UI").apply {
            isDaemon = true
            ownerThread = this
        }
    }

    init {
        // Materialise the thread now so isOwnerThread is answerable before
        // the first scheduled task runs.
        executor.submit { }.get(5, TimeUnit.SECONDS)
    }

    fun isOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    /**
     * Runs [task] now when on the owner thread, otherwise queues it while
     * the executor is live.
     *
     * @return true when the task ran or was accepted for dispatch, false when
     * shutdown had already made a safe dispatch impossible.
     */
    fun dispatch(task: Runnable): Boolean = dispatchSafely(
        isOwnerThread = ::isOwnerThread,
        isAcceptingTasks = acceptingTasks::get,
        enqueue = { runnable ->
            try {
                executor.execute(runnable)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Raced a shutdown between the acceptance check and the
                // submit, same outcome as being rejected up front
            }
        },
        task = task,
    )

    inline fun dispatch(crossinline task: () -> Unit): Boolean = dispatch(Runnable { task() })

    override fun close() {
        acceptingTasks.set(false)
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}

/** Separated from the executor so shutdown-race semantics remain unit-testable. */
internal fun dispatchSafely(
    isOwnerThread: () -> Boolean,
    isAcceptingTasks: () -> Boolean,
    enqueue: (Runnable) -> Unit,
    task: Runnable,
): Boolean {
    if (isOwnerThread()) {
        task.run()
        return true
    }
    if (!isAcceptingTasks()) return false

    enqueue(Runnable {
        // The enqueue is allowed to execute inline during a shutdown race.
        // Never let that implementation detail become an off-thread mutation.
        if (isOwnerThread()) task.run()
    })
    return true
}
