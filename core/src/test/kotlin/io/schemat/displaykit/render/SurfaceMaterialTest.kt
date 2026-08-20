package io.schemat.displaykit.render

import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.ui.OverlayCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurfaceMaterialTest {

    @Test
    fun blockMaterialWrapsABlockStateRef() {
        val m = SurfaceMaterial.Block(BlockStateRef("minecraft:lime_stained_glass"))
        assertEquals("minecraft:lime_stained_glass", m.state.id)
    }

    @Test
    fun spriteMaterialCarriesAnArbitraryTint() {
        // The whole point: escape the 16-dye stained-glass palette.
        val green = DkColor.fromRGB(0, 255, 136)
        val m = SurfaceMaterial.Sprite(SpriteId("gui", "widget/button"), tint = green)
        assertEquals(green, m.tint)
    }

    @Test
    fun blockStateRefConvertsImplicitlyForExistingCallers() {
        val m: SurfaceMaterial = BlockStateRef("minecraft:stone").asMaterial()
        assertTrue(m is SurfaceMaterial.Block)
    }

    @Test
    fun overlayCellDefaultsHoverMaterialToNull() {
        val cell = OverlayCell(
            worldX = 0.0, worldY = 64.0, worldZ = 0.0,
            cellSize = 8f,
            material = SurfaceMaterial.Block(BlockStateRef("minecraft:stone"))
        )
        assertEquals(null, cell.hoverMaterial)
        assertEquals(false, cell.interactive)
    }
}
