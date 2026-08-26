package io.schemat.displaykit.fabric.thread

import net.minecraft.server.MinecraftServer

/**
 * Safely crosses from a packet/event-loop thread onto Minecraft's owner thread.
 *
 * `MinecraftServer.execute` may run a task inline once shutdown has begun. A
 * plain `server.execute { ... }` is therefore not a sufficient thread boundary
 * for disconnect callbacks: the callback can unexpectedly continue on Netty.
 * This dispatcher rejects work after the server stops accepting tasks and the
 * queued wrapper verifies ownership again before invoking user code.
 */
object ServerThreadDispatcher {
    /**
     * Runs [task] now on the owner thread or queues it while the server is live.
     *
     * @return `true` when the task ran or was accepted for dispatch; `false`
     * when shutdown had already made a safe dispatch impossible.
     */
    @JvmStatic
    fun dispatch(server: MinecraftServer, task: Runnable): Boolean = dispatchSafely(
        isOwnerThread = server::isSameThread,
        isAcceptingTasks = server::isRunning,
        enqueue = server::execute,
        task = task,
    )

    inline fun dispatch(server: MinecraftServer, crossinline task: () -> Unit): Boolean =
        dispatch(server, Runnable { task() })
}

/** Separated from Minecraft so shutdown-race semantics remain unit-testable. */
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
        // `enqueue` is allowed to execute inline during a shutdown race. Never
        // let that implementation detail turn into an off-thread mutation.
        if (isOwnerThread()) task.run()
    })
    return true
}
