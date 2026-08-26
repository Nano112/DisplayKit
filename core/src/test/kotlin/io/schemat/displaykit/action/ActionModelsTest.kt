package io.schemat.displaykit.action

import io.schemat.displaykit.state.StateObserver
import io.schemat.displaykit.state.StateScope
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActionModelsTest {
    @Test
    fun confirmationRequiresAnExplicitSecondTrigger() {
        val scope = StateScope()
        val confirm = ConfirmAction(scope)
        var calls = 0

        assertEquals(ConfirmationResult.ARMED, confirm.trigger { calls++ })
        assertTrue(confirm.isArmed)
        assertEquals(0, calls)
        assertEquals(ConfirmationResult.CONFIRMED, confirm.trigger { calls++ })
        assertFalse(confirm.isArmed)
        assertEquals(1, calls)
    }

    @Test
    fun confirmationExpiresWithoutFeatureOwnedState() {
        val scope = StateScope()
        val confirm = ConfirmAction(scope, expiryTicks = 3)
        confirm.trigger {}
        assertEquals(3, confirm.remainingTicks)
        assertFalse(confirm.tick(2))
        assertTrue(confirm.isArmed)
        assertTrue(confirm.tick())
        assertFalse(confirm.isArmed)
        assertEquals(0, confirm.remainingTicks)
    }

    @Test
    fun asyncActionMarshalsCompletionAndRejectsOverlap() {
        val scope = StateScope()
        val queue = ArrayDeque<() -> Unit>()
        val action = AsyncAction<Int>(scope, UiDispatcher { queue.addLast(it) })
        val statuses = mutableListOf<OperationStatus<Int>>()
        action.status.subscribe(StateObserver(statuses::add))
        val future = CompletableFuture<Int>()

        assertTrue(action.start("Working") { future })
        assertFalse(action.start { CompletableFuture.completedFuture(99) })
        assertIs<OperationStatus.Running>(action.status.value)
        future.complete(42)
        assertIs<OperationStatus.Running>(action.status.value, "completion must wait for the UI dispatcher")

        queue.removeFirst()()
        assertEquals(42, assertIs<OperationStatus.Succeeded<Int>>(action.status.value).value)
        assertEquals(2, statuses.size)
    }

    @Test
    fun cancellationIgnoresAStaleCompletion() {
        val scope = StateScope()
        val queue = ArrayDeque<() -> Unit>()
        val action = AsyncAction<Int>(scope, UiDispatcher { queue.addLast(it) })
        val future = CompletableFuture<Int>()
        action.start { future }

        assertTrue(action.cancel())
        future.complete(7)
        while (queue.isNotEmpty()) queue.removeFirst()()

        assertIs<OperationStatus.Cancelled>(action.status.value)
    }

    @Test
    fun synchronousTaskFailureBecomesStatus() {
        val scope = StateScope()
        val action = AsyncAction<Int>(scope)

        assertTrue(action.start { error("nope") })

        assertEquals("nope", assertIs<OperationStatus.Failed>(action.status.value).error.message)
    }
}
