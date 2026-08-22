package io.schemat.displaykit.fabric.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first test in this module, which had no test source set at all.
 *
 * That absence is why its defects reached the client rather than a build: the
 * surface ticker's grab path and the pack-sync ritual both shipped broken and
 * were caught by eye, in-world. [GrowthBudget] exists as a separate class
 * precisely so this accounting is reachable without a live Minecraft server.
 */
class GrowthBudgetTest {

    @Test
    fun warmUpIsFreeAndTheBudgetTripsOnlyAfterIt() {
        val b = GrowthBudget(settleAfter = 4)

        repeat(4) { i ->
            b.record("picker")
            assertFalse(
                b.isLeaking("picker"),
                "growth ${i + 1} of 4 is warm-up and must not be reported as a leak"
            )
        }
        b.record("picker")
        assertTrue(b.isLeaking("picker"), "the 5th growth exceeds a budget of 4 and must trip")
    }

    @Test
    fun recordReturnsTheRunningTotal() {
        val b = GrowthBudget(settleAfter = 2)
        assertEquals(1, b.record("w"))
        assertEquals(2, b.record("w"))
        assertEquals(3, b.record("w"))
        assertEquals(3, b.eventsFor("w"))
    }

    @Test
    fun windowsDoNotShareABudget() {
        // Distinct windows pooling into one counter would make a busy window
        // trip the diagnostic on a quiet window's behalf, naming the wrong
        // culprit -- worse than no diagnostic.
        val b = GrowthBudget(settleAfter = 1)
        repeat(5) { b.record("picker") }

        assertTrue(b.isLeaking("picker"))
        assertFalse(b.isLeaking("terminal"), "the terminal never grew and must not be implicated")
        assertEquals(0, b.eventsFor("terminal"))
    }

    @Test
    fun forgetClearsOneWindowAndLeavesTheRest() {
        val b = GrowthBudget(settleAfter = 0)
        b.record("picker")
        b.record("terminal")

        b.forget("picker")

        assertEquals(0, b.eventsFor("picker"), "a reopened window starts its warm-up over")
        assertFalse(b.isLeaking("picker"))
        assertEquals(1, b.eventsFor("terminal"))
    }

    @Test
    fun resetClearsEverything() {
        val b = GrowthBudget(settleAfter = 0)
        b.record("a"); b.record("b")
        b.reset()
        assertEquals(0, b.eventsFor("a"))
        assertEquals(0, b.eventsFor("b"))
    }

    @Test
    fun aZeroBudgetTripsOnTheVeryFirstGrowth() {
        val b = GrowthBudget(settleAfter = 0)
        assertFalse(b.isLeaking("w"), "nothing recorded yet")
        b.record("w")
        assertTrue(b.isLeaking("w"))
    }

    @Test
    fun aNegativeBudgetIsRejected() {
        assertFailsWith<IllegalArgumentException> { GrowthBudget(settleAfter = -1) }
    }

    @Test
    fun concurrentRecordsAreNotLost() {
        // Paints are driven from the server thread while the hotbar-scroll
        // packet handler runs on a netty thread (it cancels at HEAD, before
        // ensureRunningOnSameThread), so this counter is genuinely contended.
        val b = GrowthBudget(settleAfter = Int.MAX_VALUE)
        val threads = (1..8).map {
            Thread { repeat(500) { b.record("picker") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(4000, b.eventsFor("picker"), "a lost update means the diagnostic under-counts")
    }
}
