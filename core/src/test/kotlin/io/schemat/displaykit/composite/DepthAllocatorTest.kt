package io.schemat.displaykit.composite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The one authority that orders every medium in a composite.
 *
 * Both defects this guards against actually shipped, and both were found by
 * looking at a screenshot rather than by a test, because the logic behind them
 * was only reachable through a render pass. That is why this is plain
 * arithmetic over plain data.
 */
class DepthAllocatorTest {

    private val eps = 1e-6f

    @Test
    fun coplanarElementsShareOneDepth() {
        // THE WEDGE. Depth used to be elementOrdinal * step, so a ~120-element
        // page spanned 1.2 blocks and read as a window tilted into a ramp.
        // Elements sharing a key are coplanar and must land on one plane
        // however many of them there are.
        val page = (1..120).map { DepthLayer(key = 0.0) }
        val z = DepthAllocator.allocate(page)

        assertEquals(1, z.size, "120 coplanar elements must occupy exactly one plane")
        assertEquals(0f, z.getValue(0.0))
        assertEquals(0f, DepthAllocator.totalDepth(page), eps, "a flat page must be flat")
    }

    @Test
    fun distinctKeysStepForwardInOrder() {
        val z = DepthAllocator.allocate(
            listOf(DepthLayer(0.0), DepthLayer(1.0), DepthLayer(2.0)),
            separation = 0.01f
        )
        assertEquals(0.00f, z.getValue(0.0), eps)
        assertEquals(0.01f, z.getValue(1.0), eps)
        assertEquals(0.02f, z.getValue(2.0), eps)
    }

    @Test
    fun aFractionalKeySlotsStrictlyBetweenItsNeighbours() {
        // CORNER OCCLUSION. A nine-slice frame's flat fills must sit strictly
        // between the corner sprites and whatever is above them, or the
        // occlusion is a coin flip. recordFrame expresses that as baseKey+0.5.
        val z = DepthAllocator.allocate(
            listOf(DepthLayer(0.0), DepthLayer(0.5), DepthLayer(1.0))
        )
        assertTrue(z.getValue(0.0) < z.getValue(0.5), "fill must sit in front of the corners")
        assertTrue(z.getValue(0.5) < z.getValue(1.0), "and behind the layer above")
    }

    @Test
    fun aThickLayerIsClearedBeforeTheNextOne() {
        // The whole reason this component exists. A block display half a block
        // deep must not have the next layer allocated inside it.
        val z = DepthAllocator.allocate(
            listOf(
                DepthLayer(key = 0.0, thickness = 0.5f),
                DepthLayer(key = 1.0, thickness = 0f)
            ),
            separation = 0.01f
        )
        assertEquals(0f, z.getValue(0.0), eps)
        assertEquals(
            0.51f, z.getValue(1.0), eps,
            "the layer in front must clear the block's real volume, not just one step"
        )
    }

    @Test
    fun theThickestMemberOfAGroupDefinesItsClearance() {
        // A coplanar group holding a flat label AND a block display is as deep
        // as the block. Taking the first, last or average would let the next
        // layer intersect the block.
        val z = DepthAllocator.allocate(
            listOf(
                DepthLayer(0.0, 0f),
                DepthLayer(0.0, 0.25f),
                DepthLayer(0.0, 0.1f),
                DepthLayer(1.0, 0f)
            ),
            separation = 0.01f
        )
        assertEquals(0.26f, z.getValue(1.0), eps)
    }

    @Test
    fun flatLayersReduceToTheOldStepBehaviour() {
        // Backwards compatibility with Surface's existing rule: with every
        // thickness zero, depth is rank * separation exactly as before.
        val keys = listOf(0.0, 3.0, 7.0, 7.5)
        val z = DepthAllocator.allocate(keys.map { DepthLayer(it) }, separation = 0.01f)

        keys.sorted().forEachIndexed { rank, key ->
            assertEquals(rank * 0.01f, z.getValue(key), eps, "key $key")
        }
    }

    @Test
    fun inputOrderDoesNotChangeTheResult() {
        // A scene built in a different order must render identically, or
        // depth becomes a function of construction order -- unreproducible,
        // and the hardest kind of visual bug to chase.
        val layers = listOf(
            DepthLayer(2.0, 0.3f),
            DepthLayer(0.0, 0f),
            DepthLayer(1.0, 0.1f),
            DepthLayer(0.0, 0.2f)
        )
        assertEquals(
            DepthAllocator.allocate(layers),
            DepthAllocator.allocate(layers.reversed())
        )
        assertEquals(
            DepthAllocator.allocate(layers),
            DepthAllocator.allocate(layers.shuffled(java.util.Random(7)))
        )
    }

    @Test
    fun totalDepthIsBoundedByTheContentNotTheElementCount() {
        // The wedge assertion, stated as the invariant a panel can check:
        // adding elements to existing planes must not deepen the panel.
        val fewElements = listOf(DepthLayer(0.0), DepthLayer(1.0))
        val manyElements = List(500) { DepthLayer(if (it % 2 == 0) 0.0 else 1.0) }

        assertEquals(
            DepthAllocator.totalDepth(fewElements),
            DepthAllocator.totalDepth(manyElements),
            eps,
            "500 elements over 2 planes must be no deeper than 2 elements over 2 planes"
        )
        assertEquals(0.01f, DepthAllocator.totalDepth(manyElements), eps)
    }

    @Test
    fun totalDepthIncludesTheFrontLayersOwnVolume() {
        val layers = listOf(DepthLayer(0.0, 0f), DepthLayer(1.0, 0.4f))
        // front plane at 0.01, plus its own 0.4 of volume
        assertEquals(0.41f, DepthAllocator.totalDepth(layers, separation = 0.01f), eps)
    }

    @Test
    fun anEmptySceneAllocatesNothing() {
        assertTrue(DepthAllocator.allocate(emptyList()).isEmpty())
        assertEquals(0f, DepthAllocator.totalDepth(emptyList()), eps)
    }

    @Test
    fun aSingleLayerSitsAtZero() {
        val z = DepthAllocator.allocate(listOf(DepthLayer(42.0, 0.2f)))
        assertEquals(0f, z.getValue(42.0), eps, "the backmost layer defines the origin")
    }

    @Test
    fun negativeKeysOrderCorrectly() {
        // A caller putting something BEHIND the existing backmost layer --
        // a backing slab, a drop shadow -- must not have it land in front.
        val z = DepthAllocator.allocate(listOf(DepthLayer(0.0), DepthLayer(-1.0)))
        assertTrue(z.getValue(-1.0) < z.getValue(0.0))
    }

    @Test
    fun zeroSeparationIsAllowedForDeliberatelyTouchingSurfaces() {
        val z = DepthAllocator.allocate(
            listOf(DepthLayer(0.0, 0.5f), DepthLayer(1.0)),
            separation = 0f
        )
        assertEquals(0.5f, z.getValue(1.0), eps, "flush against the back layer's front face")
    }

    @Test
    fun malformedInputIsRejectedWhereTheMistakeIs() {
        assertFailsWith<IllegalArgumentException> { DepthLayer(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { DepthLayer(0.0, -0.1f) }
        assertFailsWith<IllegalArgumentException> { DepthLayer(0.0, Float.NaN) }
        assertFailsWith<IllegalArgumentException> {
            DepthAllocator.allocate(listOf(DepthLayer(0.0)), separation = -1f)
        }
    }

    @Test
    fun aMixedMediaPanelComesOutInTheAuthoredOrder() {
        // The scene the whole design exists to support: a block-display bezel
        // BEHIND a sprite-composited panel, with text IN FRONT. Every one a
        // different medium, one authority ordering them.
        val bezel = 0.0
        val panel = 1.0
        val text = 2.0
        val z = DepthAllocator.allocate(
            listOf(
                DepthLayer(bezel, thickness = 0.25f), // real block volume
                DepthLayer(panel, thickness = 0f),    // sprite glyph plane
                DepthLayer(text, thickness = 0f)      // standalone text display
            ),
            separation = 0.01f
        )

        assertTrue(z.getValue(bezel) < z.getValue(panel))
        assertTrue(z.getValue(panel) < z.getValue(text))
        assertTrue(
            z.getValue(panel) >= z.getValue(bezel) + 0.25f,
            "the panel must sit clear of the bezel's front face, not inside it"
        )
        assertEquals(0.27f, DepthAllocator.totalDepth(
            listOf(DepthLayer(bezel, 0.25f), DepthLayer(panel), DepthLayer(text))
        ), eps)
    }
}
