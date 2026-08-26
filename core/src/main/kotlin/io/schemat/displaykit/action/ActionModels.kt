package io.schemat.displaykit.action

import io.schemat.displaykit.state.State
import io.schemat.displaykit.state.StateScope
import io.schemat.displaykit.platform.Scheduler
import io.schemat.displaykit.platform.TaskHandle
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future

enum class ConfirmationState { IDLE, ARMED }
enum class ConfirmationResult { ARMED, CONFIRMED }

/**
 * Two-step confirmation with deterministic tick expiry.
 *
 * Supply a platform [scheduler] for automatic expiry, or call [tick] from an
 * existing lifecycle tick. The action is owned by [scope], so delayed work is
 * cancelled when the UI closes.
 */
class ConfirmAction @JvmOverloads constructor(
    private val scope: StateScope,
    val expiryTicks: Int = 60,
    private val scheduler: Scheduler? = null,
) : AutoCloseable {
    private val mutableState = scope.mutable(ConfirmationState.IDLE)
    val state: State<ConfirmationState> get() = mutableState
    val isArmed: Boolean get() = mutableState.value == ConfirmationState.ARMED
    var remainingTicks: Int = 0
        private set
    private var expiryTask: TaskHandle? = null
    private var closed = false

    init {
        require(expiryTicks > 0) { "Confirmation expiry must be positive" }
        scope.own(this)
    }

    fun trigger(onConfirmed: () -> Unit): ConfirmationResult {
        check(!closed) { "ConfirmAction is closed." }
        if (!isArmed) {
            mutableState.set(ConfirmationState.ARMED)
            armExpiry()
            return ConfirmationResult.ARMED
        }
        disarm()
        onConfirmed()
        return ConfirmationResult.CONFIRMED
    }

    fun cancel(): Boolean {
        check(!closed) { "ConfirmAction is closed." }
        val changed = isArmed
        disarm()
        return changed
    }

    /** Advance a scheduler-free confirmation by logical game ticks. */
    @JvmOverloads
    fun tick(ticks: Int = 1): Boolean {
        check(!closed) { "ConfirmAction is closed." }
        require(ticks >= 0) { "Confirmation ticks cannot be negative" }
        if (!isArmed || ticks == 0) return false
        remainingTicks = (remainingTicks - ticks).coerceAtLeast(0)
        if (remainingTicks > 0) return false
        disarm()
        return true
    }

    override fun close() {
        if (closed) return
        expiryTask?.cancel()
        expiryTask = null
        remainingTicks = 0
        if (!scope.isClosed) mutableState.set(ConfirmationState.IDLE)
        closed = true
    }

    private fun armExpiry() {
        expiryTask?.cancel()
        remainingTicks = expiryTicks
        expiryTask = scheduler?.scheduleDelayed(expiryTicks.toLong(), Runnable {
            if (!scope.isClosed && isArmed) disarm()
        })
    }

    private fun disarm() {
        expiryTask?.cancel()
        expiryTask = null
        remainingTicks = 0
        if (!scope.isClosed) mutableState.set(ConfirmationState.IDLE)
    }
}

sealed interface OperationStatus<out T> {
    data object Idle : OperationStatus<Nothing>
    data class Running(val message: String? = null) : OperationStatus<Nothing>
    data class Succeeded<T>(val value: T, val message: String? = null) : OperationStatus<T>
    data class Failed(val error: Throwable, val message: String? = error.message) : OperationStatus<Nothing>
    data object Cancelled : OperationStatus<Nothing>
}

fun interface UiDispatcher {
    fun dispatch(block: () -> Unit)

    companion object {
        /** For tasks guaranteed to complete on the state scope's owner thread. */
        @JvmField
        val DIRECT = UiDispatcher { it() }
    }
}

/**
 * Shared asynchronous action state with overlap rejection and stale-result
 * protection. Completion is marshalled through [dispatcher] before state is
 * mutated; Fabric consumers should provide a server-thread dispatcher.
 */
class AsyncAction<T>(
    private val scope: StateScope,
    private val dispatcher: UiDispatcher = UiDispatcher.DIRECT
) : AutoCloseable {
    private val mutableStatus = scope.mutable<OperationStatus<T>>(OperationStatus.Idle)
    val status: State<OperationStatus<T>> get() = mutableStatus
    val isRunning: Boolean get() = mutableStatus.value is OperationStatus.Running

    private var generation = 0L
    private var active: CompletionStage<T>? = null
    private var closed = false

    init { scope.own(this) }

    @JvmOverloads
    fun start(
        message: String? = null,
        task: () -> CompletionStage<T>
    ): Boolean {
        check(!closed) { "AsyncAction is closed." }
        if (isRunning) return false
        val token = ++generation
        mutableStatus.set(OperationStatus.Running(message))
        val stage = try {
            task()
        } catch (error: Throwable) {
            mutableStatus.set(OperationStatus.Failed(error))
            return true
        }
        active = stage
        stage.whenComplete { value, error ->
            dispatcher.dispatch {
                if (closed || token != generation) return@dispatch
                active = null
                mutableStatus.set(
                    if (error == null) OperationStatus.Succeeded(value)
                    else OperationStatus.Failed(unwrapCompletionError(error))
                )
            }
        }
        return true
    }

    fun reset(): Boolean {
        check(!closed) { "AsyncAction is closed." }
        if (isRunning) return false
        return mutableStatus.set(OperationStatus.Idle)
    }

    fun cancel(): Boolean {
        check(!closed) { "AsyncAction is closed." }
        if (!isRunning) return false
        generation++
        (active as? Future<*>)?.cancel(true)
        active = null
        mutableStatus.set(OperationStatus.Cancelled)
        return true
    }

    override fun close() {
        if (closed) return
        generation++
        (active as? Future<*>)?.cancel(true)
        active = null
        if (!scope.isClosed && isRunning) mutableStatus.set(OperationStatus.Cancelled)
        closed = true
    }

    private fun unwrapCompletionError(error: Throwable): Throwable =
        error.cause?.takeIf {
            error is java.util.concurrent.CompletionException ||
                error is java.util.concurrent.ExecutionException
        } ?: error
}
