package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.TextMetrics
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tab is a button-sprite face with a label in front of it.
 *
 * The old `gui/widget/tab` art was open-bottomed and vertically asymmetric.
 * The replacement uses purpose-made button art and can optionally put a real
 * block-display slab behind it. What must still hold is one face plane with
 * the label strictly in front.
 *
 * A screenshot cannot prove depth either way -- a tie renders however the
 * driver feels like on a given frame, so a clean still says nothing. The
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
        s.paint { tab("t", Rect(0, 0, 200, 20), "items", selected = selected) {} }
        // Distinct Z offsets across the emitted entities, in order.
        return s.toEntities().map { it.position.z }.distinct().sorted()
    }

    @Test
    fun aTabPaintsOneBodyPlaneWithItsLabelInFront() {
        val rect = Rect(0, 0, 200, 20)
        val s = surface()
        s.paint { tab("t", rect, "items", selected = true) {} }
        val painted = s.paintedSpriteDepthsForTest()

        val bodies = painted.map { it.second }.distinct()
        assertEquals(
            1, bodies.size,
            "a tab must paint exactly ONE body plane; two coplanar fills " +
                "z-fight and two separated ones parallax apart: $painted"
        )

        val planes = s.toEntities().map { it.position.z }.distinct()
        assertTrue(
            planes.size >= 2,
            "the label must sit on a separate plane in FRONT of the body: $planes"
        )
    }

    @Test
    fun aTabsLabelIsAskedForTheCentredTextRow() {
        val rect = Rect(0, 30, 200, 20)
        val box = tabBox(rect)
        val centred = TextMetrics.rowAlignedY(box.y + (box.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2)

        val s = surface()
        s.paint { tab("t", rect, "items", selected = false) {} }
        val labelY = s.paintedLabelYsForTest()

        assertTrue(labelY.isNotEmpty(), "the tab painted no label at all")
        assertEquals(
            centred, labelY.first(),
            "the label must use the row at the geometric centre"
        )
    }

    @Test
    fun normalAndSelectedFacesStayEquallyLayered() {
        val sel = depthsOf(selected = true)
        val plain = depthsOf(selected = false)
        assertEquals(plain.size, sel.size, "state changes must not add depth planes")
    }

    @Test
    fun theLabelIsInFrontOfBothOfThem() {
        // Painter's order: face, then text on top. If the label shared the
        // face's plane it would flicker against it.
        val s = surface()
        s.paint { tab("t", Rect(0, 0, 200, 20), "items", selected = true) {} }
        val zs = s.toEntities().map { it.position.z }
        assertEquals(
            zs.maxOrNull(), zs.last(),
            "the last thing painted must also be the frontmost"
        )
    }
}
