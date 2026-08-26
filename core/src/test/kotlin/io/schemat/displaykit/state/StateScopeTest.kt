package io.schemat.displaykit.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateScopeTest {

    @Test
    fun distinctMutationsPublishAndSubscriptionsAreIdempotent() {
        val scope = StateScope()
        val state = scope.mutable(1)
        val seen = mutableListOf<Int>()
        val subscription = state.subscribe(StateObserver(seen::add), emitCurrent = true)

        assertFalse(state.set(1))
        assertTrue(state.set(2))
        subscription.close()
        subscription.close()
        state.set(3)

        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun batchCoalescesStatesAndScopeInvalidation() {
        val scope = StateScope()
        val first = scope.mutable(0)
        val second = scope.mutable("a")
        val firstSeen = mutableListOf<Int>()
        val invalidations = mutableListOf<StateInvalidation>()
        first.subscribe(StateObserver(firstSeen::add))
        scope.onInvalidated(StateInvalidationListener(invalidations::add))

        scope.batch {
            first.set(1)
            first.set(2)
            second.set("b")
        }

        assertEquals(listOf(2), firstSeen)
        assertEquals(1, invalidations.size)
        assertEquals(setOf(first, second), invalidations.single().changed)
    }

    @Test
    fun derivedStateSettlesOnceFromFinalBatchedValues() {
        val scope = StateScope()
        val x = scope.mutable(1)
        val y = scope.mutable(2)
        val sum = scope.derived(x, y) { x.value + y.value }
        val seen = mutableListOf<Int>()
        sum.subscribe(StateObserver(seen::add))

        scope.batch {
            x.set(10)
            y.set(20)
        }

        assertEquals(30, sum.value)
        assertEquals(listOf(30), seen)
    }

    @Test
    fun reentrantChangesSettleInTheSameScopeFlush() {
        val scope = StateScope()
        val first = scope.mutable(0)
        val second = scope.mutable(0)
        var invalidations = 0
        first.subscribe(StateObserver { second.set(it * 2) })
        scope.onInvalidated(StateInvalidationListener { invalidations++ })

        first.set(3)

        assertEquals(6, second.value)
        assertEquals(1, invalidations)
    }

    @Test
    fun oneThrowingObserverDoesNotStrandOtherObservers() {
        val scope = StateScope()
        val state = scope.mutable(0)
        var reached = false
        val failing = state.subscribe(StateObserver { error("boom") })
        state.subscribe(StateObserver { reached = true })

        assertFailsWith<IllegalStateException> { state.set(1) }
        assertTrue(reached)
        failing.close()
        assertTrue(state.set(2), "scope must not remain stuck in flushing state")
    }

    @Test
    fun closeStopsFurtherMutationAndSubscription() {
        val scope = StateScope()
        val state = scope.mutable(0)
        scope.close()
        scope.close()

        assertFailsWith<IllegalStateException> { state.set(1) }
        assertFailsWith<IllegalStateException> { state.subscribe(StateObserver {}) }
    }
}
