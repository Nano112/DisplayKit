package io.schemat.displaykit.surface.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A scroll must be able to reach both of its ends.
 *
 * [ScrollNode.scrollTo] snaps to [ScrollNode.stepPx] so a partial row never
 * sits flush with the viewport edge. Snapping BEFORE clamping made the far
 * end unreachable whenever `maxScroll` was not a multiple of the step: 701
 * with a step of 10 snapped to 700, and the clamp had nothing to lift it back
 * with, so a terminal's newest line stayed permanently just out of view.
 *
 * Latent for as long as surface heights happened to make `maxScroll` a tidy
 * multiple. Rounding surface heights so the measured text block matches the
 * canvas stopped that being true, which is how it surfaced.
 */
class ScrollExtremesTest {

    private fun pane(rows: Int = 80, rowH: Int = 10, viewportH: Int = 99): ScrollNode {
        val p = ScrollNode("pane")
        p.stepPx = rowH
        repeat(rows) { i ->
            val row = WidgetNode("row-$i", PxSize(100, rowH)) { _, _ -> }
            row.height = rowH
            p.addChild(row)
        }
        p.measure(PxConstraints.exactly(100, viewportH))
        p.place(PxOffset.Zero)
        return p
    }

    @Test
    fun aUniformPanesMaxScrollLandsOnTheStepGrid() {
        // The stronger guarantee, and the reason the bottom of a list stopped
        // reloading the resource pack: the viewport is floored to a whole
        // number of steps, so maxScroll is a multiple of the step and EVERY
        // position -- ends included -- keeps the content on the same vertical
        // phase. Off-phase rows take different glyph ascents, which is new
        // variants, a bigger pack and a client re-download.
        val p = pane(viewportH = 99)
        val max = p.maxScroll()
        assertTrue(max > 0, "the pane must overflow, or this test is vacuous")
        assertEquals(
            0, max % p.stepPx,
            "maxScroll $max must be a whole number of steps of ${p.stepPx}"
        )
        p.scrollTo(max)
        assertEquals(max, p.scrollPx, "the far end must still be reachable exactly")
    }

    @Test
    fun anUnalignedFarEndIsStillReachable() {
        // Flooring the viewport removes the unaligned case for uniform rows,
        // but not in general -- a list of mixed-height rows can still end
        // off-grid. The end-pinning in scrollTo has to keep working there,
        // or the last row is unreachable again.
        val p = ScrollNode("mixed")
        p.stepPx = 10
        for (i in 0 until 30) {
            // Exactly ONE odd row, so the total cannot come back to a
            // multiple of the step by accident -- my first attempt used
            // every third row and summed straight back onto the grid.
            val h = if (i == 0) 13 else 10
            val row = WidgetNode("row-$i", PxSize(100, h)) { _, _ -> }
            row.height = h
            p.addChild(row)
        }
        p.measure(PxConstraints.exactly(100, 95))
        p.place(PxOffset.Zero)

        val max = p.maxScroll()
        assertTrue(max > 0, "the mixed pane must overflow")
        assertTrue(max % p.stepPx != 0, "this rig must be unaligned to be worth anything")

        p.scrollTo(max)
        assertEquals(max, p.scrollPx, "the far end must be reachable even off-grid")
    }

    @Test
    fun theNearEndIsReachable() {
        val p = pane()
        p.scrollTo(p.maxScroll())
        p.scrollTo(0)
        assertEquals(0, p.scrollPx)
    }

    @Test
    fun overshootLandsOnTheEndsNotPastThem() {
        val p = pane()
        val max = p.maxScroll()
        p.scrollTo(max * 3)
        assertEquals(max, p.scrollPx)
        p.scrollTo(-500)
        assertEquals(0, p.scrollPx)
    }

    @Test
    fun interiorPositionsStillSnapToTheStep() {
        // Pinning the ends must not disable snapping in between, or a scroll
        // can stop mid-row and the viewport edge blanks -- the reason
        // snapping exists at all.
        val p = pane()
        p.scrollTo(43)
        assertEquals(40, p.scrollPx, "43 must snap down to the nearest step")
        p.scrollTo(46)
        assertEquals(50, p.scrollPx, "46 must snap up to the nearest step")
    }
}
