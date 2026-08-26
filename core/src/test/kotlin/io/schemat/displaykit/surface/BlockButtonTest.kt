package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.VirtualBlockDisplay
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlockButtonTest {

    @BeforeTest fun setUp() { SliceGlyphSource.installed = null }
    @AfterTest fun tearDown() { SliceGlyphSource.installed = null }

    private fun surface() =
        Surface(400, 200, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 4f)
            .apply { renderMode = RenderMode.ENTITIES }

    @Test
    fun allButtonStateSpritesArePresentAndWarmable() {
        for (name in listOf("button", "button_highlighted", "button_disabled")) {
            assertNotNull(SpriteIndex.bundled.get(SpriteId("gui", "widget/$name")))
        }
        assertEquals(3, BlockButton.statesFor().size)
    }

    @Test
    fun widthIsAnExactNineSliceSize() {
        val sprite = assertNotNull(SpriteIndex.bundled.get(SpriteId("gui", "widget/button")))
        for (want in listOf(1, 200, 201, 390, 500)) {
            val width = BlockButton.widthFor(want)
            assertTrue(width >= maxOf(want, BlockButton.MIN_WIDTH))
            assertTrue(NineSliceLayout.tilesEvenly(sprite, width, BlockButton.HEIGHT))
        }
    }

    @Test
    fun blockFaceAndLabelAreOneLaminatedAssembly() {
        val s = surface()
        s.paint {
            blockButton(
                "b", Rect(10, 20, BlockButton.MIN_WIDTH, BlockButton.HEIGHT), "ITEMS",
                state = BlockButton.State.SELECTED,
                base = BlockStateRef("minecraft:polished_deepslate")
            )
        }

        val entities = s.toEntities()
        val block = entities.filterIsInstance<VirtualBlockDisplay>().single()
        val flat = entities.filterNot { it is VirtualBlockDisplay }
        val blockMatrix = block.transformation.toFloatArray()
        val blockFront = blockMatrix[14] + blockMatrix[10]
        val faceDepth = flat.first().position.z - block.position.z
        val labelDepth = flat.last().position.z - block.position.z

        assertTrue(faceDepth - blockFront in 0.0..0.0002)
        assertTrue(labelDepth - faceDepth in 0.0..0.001)
        assertEquals(listOf("b"), s.hitRects().map { it.id })
    }

    @Test
    fun stretchedButtonLabelStaysInFrontOfEveryNineSliceCoating() {
        for (mode in listOf(RenderMode.ENTITIES, RenderMode.COMPOSITED)) {
            SliceGlyphSource.installed = if (mode == RenderMode.COMPOSITED) object : SliceGlyphSource {
                override fun request(id: SpriteId) = Unit
                override fun codepointFor(id: SpriteId, srcX: Int, srcY: Int, ascent: Int): Int? = null
                override fun advanceFor(id: SpriteId, srcX: Int, srcY: Int): Int? = null
            } else null

            val s = Surface(400, 200, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 5.2f).apply {
                renderMode = mode
            }
            s.paint {
                blockButton(
                    "wide", Rect(10, 20, 300, BlockButton.HEIGHT), "Decrease Signal",
                    base = BlockStateRef("minecraft:polished_deepslate")
                )
            }

            val flat = s.toEntities().filterNot { it is VirtualBlockDisplay }
            val label = flat.single { (it as io.schemat.displaykit.render.VirtualTextDisplay).text.plain() == "Decrease Signal" }
            val coatings = flat.filterNot { it === label }
            assertTrue(coatings.isNotEmpty())
            assertTrue(
                coatings.all { label.position.z > it.position.z },
                "$mode label must be in front of every stretched-frame coating"
            )
            assertTrue(
                label.position.z - coatings.minOf { it.position.z } < Surface.LAYER_Z_STEP,
                "$mode button assembly must remain laminated"
            )
        }
    }

    @Test
    fun blockVolumeStaysBehindItsFaceWithoutSpreadingTheSurface() {
        fun flatLayerGap(withButtonBase: Boolean): Double {
            val s = surface()
            s.paint {
                blockButton(
                    "b", Rect(10, 20, BlockButton.MIN_WIDTH, BlockButton.HEIGHT), "ITEMS",
                    base = if (withButtonBase) BlockStateRef("minecraft:polished_deepslate") else null,
                    baseThickness = 0.1f
                )
            }
            val text = s.toEntities().filterNot { it is VirtualBlockDisplay }
            return text.last().position.z - text.first().position.z
        }

        assertEquals(flatLayerGap(false), flatLayerGap(true), 1e-6)

        val s = surface()
        s.paint {
            blockButton(
                "b", Rect(10, 20, BlockButton.MIN_WIDTH, BlockButton.HEIGHT), "ITEMS",
                base = BlockStateRef("minecraft:polished_deepslate"),
                baseThickness = 0.1f
            )
        }
        val entities = s.toEntities()
        val block = entities.filterIsInstance<VirtualBlockDisplay>().single()
        val face = entities.filterNot { it is VirtualBlockDisplay }.first()
        val blockMatrix = block.transformation.toFloatArray()
        val blockBack = blockMatrix[14]
        val blockFront = blockBack + blockMatrix[10]
        val faceDepth = face.position.z - block.position.z

        assertEquals(0.1f, blockMatrix[10], 1e-5f)
        assertEquals(0f, blockBack, 1e-5f, "button back face must stay on the panel plane")
        assertTrue(blockBack < blockFront)
        assertTrue(faceDepth >= blockFront - 1e-5f)
    }

    @Test
    fun selectedAndNormalFacesDiffer() {
        val normal = BlockButton.entry(BlockButton.State.NORMAL)?.id
        val selected = BlockButton.entry(BlockButton.State.SELECTED)?.id
        assertTrue(normal != selected)
    }

    @Test
    fun anUndersizedButtonDrawsNothingAndRegistersNoHitTarget() {
        val s = surface()
        s.paint { blockButton("b", Rect(0, 0, 199, 20), "x") }
        assertTrue(s.toEntities().isEmpty())
        assertTrue(s.hitRects().isEmpty())
    }
}
