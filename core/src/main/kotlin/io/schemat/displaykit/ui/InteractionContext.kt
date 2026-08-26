package io.schemat.displaykit.ui

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Semantic input understood by every DisplayKit presentation. */
enum class SemanticInteraction {
    FOCUS_PREVIOUS,
    FOCUS_NEXT,
    INVOKE,
    BACK,
    CANCEL,
    PRIMARY_WORLD_ACTION,
    SECONDARY_WORLD_ACTION,
}

/** Coarse ownership layer used to make input precedence explicit. */
enum class InteractionLayer(val defaultPriority: Int) {
    TOOLBAR(400),
    SURFACE(300),
    OVERLAY(200),
    WORLD_TOOL(100),
}

/** One physical input translated into a renderer-neutral semantic event. */
data class InteractionInput(
    val semantic: SemanticInteraction,
    val source: String,
    val physicalKey: String = semantic.name,
    val sameSourceCooldownNanos: Long = 0,
) {
    init {
        require(source.isNotBlank()) { "Interaction source cannot be blank" }
        require(physicalKey.isNotBlank()) { "Interaction physical key cannot be blank" }
        require(sameSourceCooldownNanos >= 0) { "Interaction cooldown cannot be negative" }
    }
}

fun interface InteractionHandler {
    /** Return true only when this candidate performed or intentionally swallowed the action. */
    fun handle(input: InteractionInput): Boolean
}

/** A possible consumer. Higher priority wins; declaration order breaks ties. */
data class InteractionCandidate(
    val layer: InteractionLayer,
    val id: String,
    val priority: Int = layer.defaultPriority,
    val handler: InteractionHandler,
) {
    init { require(id.isNotBlank()) { "Interaction candidate id cannot be blank" } }
}

enum class InteractionOutcome { CONSUMED, MISSED, DEDUPLICATED }

data class InteractionDecision(
    val input: InteractionInput,
    val outcome: InteractionOutcome,
    val consumedLayer: InteractionLayer? = null,
    val consumedBy: String? = null,
    val attempted: List<String> = emptyList(),
) {
    val consumed: Boolean get() = outcome == InteractionOutcome.CONSUMED
}

data class InteractionDiagnostics(
    val accepted: Long,
    val consumed: Long,
    val missed: Long,
    val deduplicated: Long,
    val lastDecision: InteractionDecision?,
)

/**
 * Per-player arbitration for toolbar, retained surface, overlay and world-tool input.
 *
 * [dispatch] is the physical-input boundary: it applies same-source cooldown and
 * cross-source deduplication before trying candidates in explicit priority order.
 * [arbitrate] is for a handler that has already captured the physical event and
 * wants to choose between semantic sublayers without applying deduplication twice.
 */
class InteractionContext(
    val playerId: UUID,
    private val clockNanos: () -> Long = System::nanoTime,
    private val crossSourceWindowNanos: Long = 100_000_000L,
) {
    private data class Seen(val source: String, val atNanos: Long)

    private val lastAccepted = mutableMapOf<Pair<String, String>, Long>()
    private val lastConsumed = mutableMapOf<String, Seen>()
    private var acceptedCount = 0L
    private var consumedCount = 0L
    private var missedCount = 0L
    private var deduplicatedCount = 0L
    private var latest: InteractionDecision? = null

    init { require(crossSourceWindowNanos >= 0) { "Cross-source window cannot be negative" } }

    @Synchronized
    fun dispatch(input: InteractionInput, candidates: List<InteractionCandidate>): InteractionDecision {
        validateCandidates(candidates)
        val now = clockNanos()
        val sameSourceKey = input.physicalKey to input.source
        val previousAccepted = lastAccepted[sameSourceKey]
        val sameSourceDuplicate = input.sameSourceCooldownNanos > 0 &&
            previousAccepted != null && now - previousAccepted < input.sameSourceCooldownNanos
        val previousConsumed = lastConsumed[input.physicalKey]
        val crossSourceDuplicate = previousConsumed != null &&
            previousConsumed.source != input.source &&
            now - previousConsumed.atNanos < crossSourceWindowNanos

        if (sameSourceDuplicate || crossSourceDuplicate) {
            deduplicatedCount++
            return remember(InteractionDecision(input, InteractionOutcome.DEDUPLICATED))
        }

        lastAccepted[sameSourceKey] = now
        acceptedCount++
        val decision = arbitrateInternal(input, candidates)
        if (decision.consumed) {
            consumedCount++
            lastConsumed[input.physicalKey] = Seen(input.source, now)
        } else {
            missedCount++
        }
        return remember(decision)
    }

    @Synchronized
    fun arbitrate(input: InteractionInput, candidates: List<InteractionCandidate>): InteractionDecision {
        validateCandidates(candidates)
        return remember(arbitrateInternal(input, candidates))
    }

    @Synchronized
    fun diagnostics(): InteractionDiagnostics = InteractionDiagnostics(
        acceptedCount, consumedCount, missedCount, deduplicatedCount, latest,
    )

    @Synchronized
    fun reset() {
        lastAccepted.clear()
        lastConsumed.clear()
        acceptedCount = 0
        consumedCount = 0
        missedCount = 0
        deduplicatedCount = 0
        latest = null
    }

    private fun arbitrateInternal(
        input: InteractionInput,
        candidates: List<InteractionCandidate>,
    ): InteractionDecision {
        val attempted = mutableListOf<String>()
        candidates.withIndex()
            .sortedWith(compareByDescending<IndexedValue<InteractionCandidate>> { it.value.priority }
                .thenBy { it.index })
            .forEach { (_, candidate) ->
                attempted += candidate.id
                if (candidate.handler.handle(input)) {
                    return InteractionDecision(
                        input,
                        InteractionOutcome.CONSUMED,
                        candidate.layer,
                        candidate.id,
                        attempted.toList(),
                    )
                }
            }
        return InteractionDecision(input, InteractionOutcome.MISSED, attempted = attempted)
    }

    private fun validateCandidates(candidates: List<InteractionCandidate>) {
        require(candidates.map { it.id }.distinct().size == candidates.size) {
            "Interaction candidate ids must be unique within a dispatch"
        }
    }

    private fun remember(decision: InteractionDecision): InteractionDecision {
        latest = decision
        return decision
    }
}

/** Lifecycle-owned registry used by platform adapters and application integrations. */
object InteractionContexts {
    private val contexts = ConcurrentHashMap<UUID, InteractionContext>()

    @JvmStatic
    fun forPlayer(playerId: UUID): InteractionContext =
        contexts.computeIfAbsent(playerId) { InteractionContext(it) }

    @JvmStatic
    fun existing(playerId: UUID): InteractionContext? = contexts[playerId]

    @JvmStatic
    fun remove(playerId: UUID) { contexts.remove(playerId)?.reset() }

    @JvmStatic
    fun clear() { contexts.values.forEach { it.reset() }; contexts.clear() }
}
