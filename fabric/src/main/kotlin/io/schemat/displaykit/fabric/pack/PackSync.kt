package io.schemat.displaykit.fabric.pack

import io.schemat.displaykit.pack.SpacingFontProvider
import io.schemat.displaykit.pack.SpriteFontProvider
import io.schemat.displaykit.pack.SpriteSliceProvider
import io.schemat.displaykit.sprite.SpriteGlyphs
import org.slf4j.LoggerFactory

/**
 * Register the glyph asset providers, run a paint, and resend the resource
 * pack if that paint allocated new glyph variants.
 *
 * Every surface that composites sprites needs this exact sequence, and
 * skipping it is SILENT: the client keeps whatever pack it already has, so
 * every glyph the new window needs renders as a missing-glyph box.
 * `TerminalWindow` shipped without it and was entirely illegible.
 *
 * It lives here, in the shared platform layer, rather than beside any one
 * window. Three separate defects in this subsystem have had the same shape --
 * a rule implemented per-window, then forgotten by the next window: the pack
 * ritual itself (copied four times), the scrollbar thumb's Y snap (picker
 * only), and its height quantisation. A window cannot forget what it does not
 * have to remember.
 *
 * Growth is checked on BOTH allocators a paint can grow -- [SpriteGlyphs]'s
 * whole-sprite codepoints and [SpriteSliceProvider]'s cropped nine-slice
 * variants. Chrome (frames, tabs, scrollbars) allocates slices, not whole
 * glyphs, so checking only one half would silently skip the resend a
 * slice-only repaint needs.
 *
 * `registerAssetProvider` is idempotent, so calling this on every
 * content-changing repaint -- not just the first -- is safe and cheap.
 * Rebuilding UNCONDITIONALLY would be wrong the other way: every connected
 * client would re-download the pack on every repaint.
 */
object PackSync {

    private val logger = LoggerFactory.getLogger("DisplayKit/PackSync")

    /**
     * Growth events tolerated per window before it is treated as a leak.
     *
     * ONE: a window warms everything it can ever draw before its first push,
     * so the build at open is expected and anything after it is a bug.
     *
     * This was 4, picked as a guess at "some warm-up is fine", and it meant
     * the guard stayed silent through exactly the leaks worth catching --
     * clicking a picker tab rebuilt the pack three times without a word in
     * the log. A diagnostic that tolerates the common case is not a
     * diagnostic.
     */
    private const val SETTLE_AFTER = 1

    private val budget = GrowthBudget(SETTLE_AFTER)

    /**
     * Run [paint] with the providers registered, resending the pack if it
     * allocated anything new. Returns true if a rebuild was triggered, so the
     * caller can decide whether to wait via
     * [FabricPackIntegration.whenPackApplied].
     *
     * [label] identifies the call site (a window name is ideal) for the leak
     * diagnostic. Distinct windows must pass distinct labels or their warm-up
     * budgets pool together and the diagnostic loses its meaning.
     */
    fun withPackSync(label: String, paint: () -> Unit): Boolean {
        FabricPackIntegration.registerAssetProvider(SpriteFontProvider)
        FabricPackIntegration.registerAssetProvider(SpacingFontProvider)
        FabricPackIntegration.registerAssetProvider(SpriteSliceProvider)

        val glyphsBefore = SpriteGlyphs.requested()
        val slicesBefore = SpriteSliceProvider.variants()

        paint()

        val glyphsAfter = SpriteGlyphs.requested()
        val slicesAfter = SpriteSliceProvider.variants()
        val grew = glyphsAfter.size > glyphsBefore.size || slicesAfter.size > slicesBefore.size
        if (!grew) return false

        val events = budget.record(label)
        if (budget.isLeaking(label)) {
            // Past warm-up and still minting. Name what was added: the
            // repeated axis IS the bug, and it is invisible in-game -- it
            // shows only as the client re-downloading the pack, which reads
            // as lag rather than as a defect. Both defects of this shape so
            // far were a scrollbar thumb whose geometry varied continuously.
            val newGlyphs = glyphsAfter.drop(glyphsBefore.size)
            logger.warn(
                "'{}' has grown the resource pack {} times (warm-up allows {}). " +
                    "Every growth costs every client a pack download. " +
                    "New this paint: {} glyph(s) {}, {} slice(s) {}. " +
                    "A geometry value that varies continuously (a thumb's Y or " +
                    "height, an ascent derived from scroll position) must be " +
                    "quantised -- see SurfaceParts.snapToLinePitch.",
                label,
                events,
                SETTLE_AFTER,
                newGlyphs.size,
                newGlyphs.take(4).map { describe(it) },
                slicesAfter.size - slicesBefore.size,
                slicesAfter.drop(slicesBefore.size).take(4)
            )
            // Naming the new variant is not enough to act on: a sprite that
            // was pre-warmed at one geometry and drawn at another looks
            // identical in the log to one that was never warmed at all. The
            // variants the SAME sprite already had are what identify the axis
            // that moved -- and if there are none, the warm-up simply missed
            // it. That distinction was the whole difficulty in tracking the
            // picker's tab-click rebuild down.
            for (g in newGlyphs.take(4)) {
                val siblings = glyphsBefore.filter { it.entry.id == g.entry.id }
                logger.warn(
                    "  {} was drawn at {} -- already warmed at {}",
                    g.entry.id,
                    describe(g),
                    if (siblings.isEmpty()) "NOTHING (never warmed)"
                    else siblings.map { describe(it) }
                )
            }
        }

        FabricPackIntegration.rebuildAndResendToAll()
        return true
    }

    /**
     * Declare [label] fully warmed. Any growth after this is reported as a leak.
     *
     * A window calls this once its open sequence has pre-warmed everything it
     * can ever draw. Without it the guard has only an event count to go on,
     * and a window reopened against an already-warm pack spends no events
     * warming -- so its first real leak looks like warm-up and passes in
     * silence.
     */
    fun settled(label: String) = budget.settle(label)

    /** A glyph variant's identity: the axes a repaint can vary. */
    private fun describe(g: SpriteGlyphs.GlyphVariant) =
        "ascent=${g.ascent} h=${g.renderHeight}"

    /** Forget [label]'s warm-up budget, e.g. when its window closes. */
    fun forget(label: String) = budget.forget(label)

    /** Growth events recorded for [label] so far. */
    fun growthEventsFor(label: String): Int = budget.eventsFor(label)

    /** Drop every recorded budget. */
    fun reset() = budget.reset()
}
