package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.render.VirtualBlockDisplay
import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A panel that mixes media in one surface: a real block with volume, sprite
 * pixels, and text.
 *
 * This is the scene the compositor design exists for. Each medium does
 * something the others cannot -- a block display is world-lit with real depth,
 * a sprite canvas is dense 2D pixels, text is crisp at any distance -- and the
 * framework's job is to let them coexist, ordered by one authority, rather
 * than making a caller pick one stack and live inside it.
 */
class MixedMediaSurfaceTest {

    private val icon = SpriteEntry(
        id = SpriteId("items", "marker"), width = 8, height = 8,
        texture = "minecraft:item/marker.png"
    )
    private val bezel = BlockStateRef("minecraft:polished_deepslate")

    @BeforeTest fun clearSliceSource() { SliceGlyphSource.installed = null }
    @AfterTest fun restoreSliceSource() { SliceGlyphSource.installed = null }

    private fun surface() =
        Surface(200, 120, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 2f)
            .apply { renderMode = RenderMode.ENTITIES }

    @Test
    fun allThreeMediaCoexistInOneSurface() {
        val s = surface()
        s.paint {
            blockPanel(bezel, Rect(0, 0, 200, 120), thickness = 0.25f)
            icon(icon, 10, 10)
            label("READY", 20, 20)
        }
        val es = s.toEntities()

        assertEquals(1, es.filterIsInstance<VirtualBlockDisplay>().size, "the bezel")
        assertEquals(2, es.filterIsInstance<VirtualTextDisplay>().size, "the sprite and the label")
    }

    @Test
    fun theBlocksVolumeIsClearedBeforeWhateverSitsInFrontOfIt() {
        // The defect this whole component exists to prevent: a flat element
        // allocated one step in front of a quarter-block-deep bezel would be
        // rendered INSIDE it, and the client resolves the overlap however it
        // likes. Depth is read off the transformation's translation column.
        val s = surface()
        s.paint {
            blockPanel(bezel, Rect(0, 0, 200, 120), thickness = 0.25f)
            elevate { label("IN FRONT", 20, 20) }
        }
        val es = s.toEntities()
        val block = es.filterIsInstance<VirtualBlockDisplay>().single()
        val text = es.filterIsInstance<VirtualTextDisplay>().single()

        // JOML column-major: m32 is the Z translation.
        val blockZ = block.transformation.toFloatArray()[14]
        // The label is placed by position, not by an in-matrix z offset, so
        // compare world Z of the two anchors against the block's own front
        // face. Both were built from the same plane at yaw 0, where the
        // surface normal is world +Z.
        val textZ = text.position.z - block.position.z

        assertTrue(
            textZ >= blockZ + 0.25f - 1e-4f,
            "text at z=$textZ must clear the bezel's front face (back $blockZ + 0.25 thick)"
        )
    }

    @Test
    fun aBlockElementKeepsItsVolumeOnACompositedSurface() {
        // Compositing collapses flat artwork, not explicit 3D media. The
        // block remains a block display and the canvas layer is allocated in
        // front of its real thickness.
        SliceGlyphSource.installed = object : SliceGlyphSource {
            override fun request(id: SpriteId) {}
            override fun codepointFor(id: SpriteId, srcX: Int, srcY: Int, ascent: Int) = 0xF8000
            override fun advanceFor(id: SpriteId, srcX: Int, srcY: Int): Int? = null
        }
        val s = Surface(200, 120, Vec3d.ZERO, targetWidthBlocks = 2f)
            .apply { renderMode = RenderMode.COMPOSITED }
        s.paint {
            blockPanel(bezel, Rect(0, 0, 200, 120), thickness = 0.25f)
            label("hi", 4, 4)
        }
        val entities = s.toEntities()
        assertEquals(1, entities.filterIsInstance<VirtualBlockDisplay>().size)
        assertEquals(1, entities.filterIsInstance<VirtualTextDisplay>().size)
    }

    @Test
    fun aFlatBlockIsRejectedRatherThanSilentlyBecomingAPlane() {
        // Zero thickness would allocate no depth, putting the next layer on
        // the block's own plane -- z-fighting that looks like a render bug.
        // fill() is the right call for a flat rectangle.
        val s = surface()
        assertFailsWith<IllegalArgumentException> {
            s.paint { blockPanel(bezel, Rect(0, 0, 40, 40), thickness = 0f) }
        }
    }

    @Test
    fun addingABlockDoesNotDisturbTheOrderOfTheFlatElements() {
        // A block appearing in a panel must not reshuffle what was already
        // there -- the sprite still sits behind the text, exactly as before.
        fun labelZ(withBlock: Boolean): Double {
            val s = surface()
            s.paint {
                if (withBlock) blockPanel(bezel, Rect(0, 0, 200, 120), thickness = 0.25f)
                icon(icon, 10, 10)
                elevate { label("X", 20, 20) }
            }
            val es = s.toEntities().filterIsInstance<VirtualTextDisplay>()
            val sprite = es.first()
            val text = es.last()
            return text.position.z - sprite.position.z
        }
        val without = labelZ(withBlock = false)
        val with = labelZ(withBlock = true)
        // Tolerance is float-epsilon, not zero: depth is accumulated in Float
        // and (0.27f - 0.26f) is not bit-identical to 0.01f. A real ordering
        // defect would move this by the block's whole 0.25 thickness, not by
        // a few billionths, so the tolerance cannot mask one.
        assertEquals(
            without, with, 1e-6,
            "the sprite-to-text gap must be unchanged by an unrelated block element " +
                "(without=$without with=$with delta=${with - without})"
        )
    }

    @Test
    fun paintingOnlyABlockStillProducesASurface() {
        val s = surface()
        s.paint { blockPanel(bezel, Rect(0, 0, 200, 120), thickness = 0.5f) }
        val es = s.toEntities()
        assertEquals(1, es.size)
        assertTrue(es.single() is VirtualBlockDisplay)
    }

    @Test
    fun theBlockCarriesTheMaterialItWasGiven() {
        val s = surface()
        s.paint { blockPanel(BlockStateRef("minecraft:copper_grate"), Rect(0, 0, 40, 40), 0.1f) }
        val block = s.toEntities().filterIsInstance<VirtualBlockDisplay>().single()
        assertEquals(BlockStateRef("minecraft:copper_grate"), block.blockState)
    }

    @Test
    fun aBlockPanelUsesCanvasPixelUnitsRatherThanGlyphUnits() {
        val s = surface()
        val rect = Rect(10, 20, 40, 30)
        s.paint { blockPanel(bezel, rect, 0.1f) }
        val block = s.toEntities().filterIsInstance<VirtualBlockDisplay>().single()
        val matrix = block.transformation.toFloatArray()
        val worldPerPixel = TextMetrics.PIXEL_SIZE * s.pixelScale

        assertEquals(worldPerPixel * rect.w, matrix[0], 1e-5f, "block width")
        assertEquals(worldPerPixel * rect.h, matrix[5], 1e-5f, "block height")
        assertEquals(0.1f, matrix[10], 1e-5f, "block thickness")
    }

    @Test
    fun aBlockAndAFillAreDifferentThings() {
        // fill() is a sprite; blockPanel() is a solid. If these ever collapsed
        // into the same output the medium choice would be a lie.
        val withFill = surface().apply { paint { fill(DkColor.WHITE, Rect(0, 0, 40, 40)) } }
        val withBlock = surface().apply { paint { blockPanel(bezel, Rect(0, 0, 40, 40), 0.1f) } }

        assertTrue(withFill.toEntities().single() is VirtualTextDisplay)
        assertTrue(withBlock.toEntities().single() is VirtualBlockDisplay)
    }
}
