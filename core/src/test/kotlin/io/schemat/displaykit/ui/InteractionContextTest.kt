package io.schemat.displaykit.ui

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InteractionContextTest {
    private val player = UUID.fromString("00000000-0000-0000-0000-000000000042")

    @Test
    fun `highest priority consumer wins exactly once`() {
        var calls = 0
        val context = InteractionContext(player)
        val input = InteractionInput(SemanticInteraction.INVOKE, "packet", "click:right")
        val decision = context.dispatch(input, listOf(
            InteractionCandidate(InteractionLayer.WORLD_TOOL, "tool") { calls++; true },
            InteractionCandidate(InteractionLayer.SURFACE, "surface") { calls++; true },
            InteractionCandidate(InteractionLayer.OVERLAY, "overlay") { calls++; true },
        ))

        assertTrue(decision.consumed)
        assertEquals(InteractionLayer.SURFACE, decision.consumedLayer)
        assertEquals("surface", decision.consumedBy)
        assertEquals(1, calls)
    }

    @Test
    fun `missed candidates fall through in stable priority order`() {
        val order = mutableListOf<String>()
        val decision = InteractionContext(player).dispatch(
            InteractionInput(SemanticInteraction.PRIMARY_WORLD_ACTION, "packet"),
            listOf(
                InteractionCandidate(InteractionLayer.OVERLAY, "first") { order += "first"; false },
                InteractionCandidate(InteractionLayer.OVERLAY, "second") { order += "second"; true },
            ),
        )
        assertEquals(listOf("first", "second"), order)
        assertEquals(listOf("first", "second"), decision.attempted)
    }

    @Test
    fun `one physical click is deduplicated across sources`() {
        var now = 1_000_000_000L
        var calls = 0
        val context = InteractionContext(player, { now }, crossSourceWindowNanos = 100)
        val candidate = InteractionCandidate(InteractionLayer.TOOLBAR, "toolbar") { calls++; true }
        assertTrue(context.dispatch(
            InteractionInput(SemanticInteraction.INVOKE, "packet", "click:right"), listOf(candidate),
        ).consumed)
        now += 50
        val duplicate = context.dispatch(
            InteractionInput(SemanticInteraction.INVOKE, "event", "click:right"), listOf(candidate),
        )
        assertEquals(InteractionOutcome.DEDUPLICATED, duplicate.outcome)
        assertEquals(1, calls)
        assertEquals(1, context.diagnostics().deduplicated)
    }

    @Test
    fun `miss does not suppress a later consumer from another source`() {
        var now = 0L
        val context = InteractionContext(player, { now }, crossSourceWindowNanos = 100)
        assertFalse(context.dispatch(
            InteractionInput(SemanticInteraction.INVOKE, "packet", "click:right"), emptyList(),
        ).consumed)
        now++
        assertTrue(context.dispatch(
            InteractionInput(SemanticInteraction.INVOKE, "event", "click:right"),
            listOf(InteractionCandidate(InteractionLayer.TOOLBAR, "toolbar") { true }),
        ).consumed)
    }

    @Test
    fun `same source cooldown and candidate ids are validated`() {
        var now = 0L
        val context = InteractionContext(player, { now })
        val input = InteractionInput(
            SemanticInteraction.INVOKE, "packet", sameSourceCooldownNanos = 10,
        )
        val candidate = InteractionCandidate(InteractionLayer.SURFACE, "surface") { true }
        context.dispatch(input, listOf(candidate))
        now = 5
        assertEquals(InteractionOutcome.DEDUPLICATED, context.dispatch(input, listOf(candidate)).outcome)
        assertFailsWith<IllegalArgumentException> {
            context.arbitrate(input, listOf(candidate, candidate))
        }
    }
}
