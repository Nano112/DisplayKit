package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A hollow tab's ground and its frame must not share a depth plane.
 *
 * Vanilla's selected-tab sprites have a transparent centre, so a
 * free-floating one needs a ground painted behind it. Painted at the SAME
 * elevation and kind as the frame, the two get an identical depth key from
 * `elevation * KINDS_PER_ELEVATION + kind`, land coplanar, and z-fight --
 * which is what shipped and what was reported as shimmer on the tab strip.
 *
 * A screenshot cannot prove this either way: a depth tie renders however the
 * driver feels like on any given frame, so a clean still says nothing. The
 * depth values are the evidence.
 */
class TabDepthTest {

    @BeforeTest fun setUp() { SliceGlyphSource.installed = null }
    @AfterTest fun tearDown() { SliceGlyphSource.installed = null }

    private fun surface() =
        Surface(400, 200, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 4f)
            .apply { renderMode = RenderMode.ENTITIES }

    private fun depthsOf(selected: Boolean): List<Double> {
        val s = surface()
        s.paint { tab("t", Rect(0, 0, 130, 30), "items", selected = selected) {} }
        // Distinct Z offsets across the emitted entities, in order.
        return s.toEntities().map { it.position.z }.distinct().sorted()
    }

    @Test
    fun aHollowTabsGroundSitsStrictlyBehindItsFrame() {
        // Named layers, not a plane count. Counting fails here: a hollow
        // nine-slice skips the substitute fills a solid one emits, so adding
        // a ground leaves the TOTAL unchanged while the ground and frame are
        // still coplanar -- which is precisely the bug.
        val rect = Rect(0, 0, 130, 30)
        val s = surface()
        s.paint { tab("t", rect, "items", selected = true) {} }
        val painted = s.paintedSpriteDepthsForTest()

        // The ground is the only sprite covering the whole rect; the frame's
        // corners are drawn at the sprite's own native size.
        val ground = painted.filter { it.first == rect }
        val frame = painted.filter { it.first != rect }

        assertTrue(ground.isNotEmpty(), "no full-rect ground was painted: $painted")
        assertTrue(frame.isNotEmpty(), "no frame pieces were painted: $painted")
        assertTrue(
            ground.maxOf { it.second } < frame.minOf { it.second },
            "the ground must sit strictly BEHIND every frame piece, else they " +
                "share a plane and z-fight -- ground=${ground.map { it.second }} " +
                "frame=${frame.map { it.second }}"
        )
    }

    @Test
    fun anOpaqueTabNeedsNoGroundAndStaysCheap() {
        // The unselected sprite is solid, so no ground is painted and nothing
        // extra is spent. Guards against blanketing every tab with a fill.
        val sel = depthsOf(selected = true)
        val plain = depthsOf(selected = false)
        assertTrue(
            plain.size <= sel.size,
            "an opaque tab must not emit more planes than a hollow one " +
                "(opaque=$plain hollow=$sel)"
        )
    }

    @Test
    fun theLabelIsInFrontOfBothOfThem() {
        // Painter's order: ground, frame, then text on top. If the label
        // shared the frame's plane it would flicker against it.
        val s = surface()
        s.paint { tab("t", Rect(0, 0, 130, 30), "items", selected = true) {} }
        val zs = s.toEntities().map { it.position.z }
        assertEquals(
            zs.maxOrNull(), zs.last(),
            "the last thing painted must also be the frontmost"
        )
    }
}
