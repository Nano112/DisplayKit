package io.schemat.displaykit.fabric.pack

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-window budget of "this paint grew the resource pack" events.
 *
 * A window legitimately grows the pack while it warms up: the first paint,
 * the first hover, the first scroll into a new row phase. What it must not do
 * is grow FOREVER -- every growth costs every connected client a full pack
 * download. A handful of events is warm-up; a steady trickle is an unbounded
 * geometry axis, and that has been the shape of the two worst defects in this
 * subsystem (a scrollbar thumb's Y, then the same thumb's height).
 *
 * Split out from [PackSync] because [PackSync] cannot run without a live
 * Minecraft server, and this accounting is the part worth testing.
 *
 * Safe for concurrent use: paints can be driven from the server thread while
 * a netty-thread packet handler drives another.
 */
internal class GrowthBudget(private val settleAfter: Int) {

    init {
        require(settleAfter >= 0) { "settleAfter must be non-negative, got $settleAfter" }
    }

    private val events = ConcurrentHashMap<String, Int>()

    /** Record a growth event for [label] and return its new running total. */
    fun record(label: String): Int = events.merge(label, 1, Int::plus) ?: 1

    /** Events recorded for [label] so far. */
    fun eventsFor(label: String): Int = events[label] ?: 0

    /**
     * True once [label] has grown the pack more times than warm-up allows.
     *
     * Strictly greater: a window that grows exactly [settleAfter] times has
     * spent its budget and no more, which is the boundary case a window that
     * warms fully and then settles is expected to sit on.
     */
    fun isLeaking(label: String): Boolean = eventsFor(label) > settleAfter

    /** Forget [label]'s budget, e.g. when its window closes. */
    fun forget(label: String) {
        events.remove(label)
    }

    /** Drop every recorded budget. */
    fun reset() {
        events.clear()
    }
}
