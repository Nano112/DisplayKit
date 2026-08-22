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
    fun theFarEndIsReachableWhenItIsNotOnAStepBoundary() {
        val p = pane()
        val max = p.maxScroll()
        assertTrue(max > 0, "the pane must overflow, or this test is vacuous")
        assertTrue(
            max % p.stepPx != 0,
            "this rig must produce an unaligned maxScroll ($max, step ${p.stepPx}) " +
                "or it cannot exercise the defect"
        )

        p.scrollTo(max)
        assertEquals(max, p.scrollPx, "the far end must be reachable exactly")
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
