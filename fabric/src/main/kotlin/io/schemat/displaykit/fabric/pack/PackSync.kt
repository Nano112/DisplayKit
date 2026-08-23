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
                glyphsAfter.size - glyphsBefore.size,
                glyphsAfter.drop(glyphsBefore.size).take(4),
                slicesAfter.size - slicesBefore.size,
                slicesAfter.drop(slicesBefore.size).take(4)
            )
        }

        FabricPackIntegration.rebuildAndResendToAll()
        return true
    }

    /** Forget [label]'s warm-up budget, e.g. when its window closes. */
    fun forget(label: String) = budget.forget(label)

    /** Growth events recorded for [label] so far. */
    fun growthEventsFor(label: String): Int = budget.eventsFor(label)

    /** Drop every recorded budget. */
    fun reset() = budget.reset()
}
