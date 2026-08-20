package io.schemat.displaykit.pack

import io.schemat.displaykit.sprite.NineSlice
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpriteSliceProviderTest {

    @BeforeTest fun reset() { SpriteGlyphs.clear(); SpriteSlicer.clear() }
    @AfterTest fun tearDown() { SpriteGlyphs.clear(); SpriteSlicer.clear() }

    private val button = SpriteEntry(
        id = SpriteId("gui", "widget/button"),
        width = 200, height = 20,
        texture = "minecraft:gui/sprites/widget/button.png",
        nineSlice = NineSlice(left = 3, top = 3, right = 3, bottom = 3)
    )

    @Test
    fun ninePatchProducesNineRegions() {
        assertEquals(9, SpriteSlicer.ninePatch(button).size)
    }

    @Test
    fun cornerRegionsUseTheBorderInsets() {
        val regions = SpriteSlicer.ninePatch(button)
        val topLeft = regions.first()
        assertEquals(0, topLeft.x)
        assertEquals(0, topLeft.y)
        assertEquals(3, topLeft.w)
        assertEquals(3, topLeft.h)
    }

    @Test
    fun centerRegionIsTheInteriorAfterBorders() {
        val center = SpriteSlicer.ninePatch(button)[4]
        assertEquals(3, center.x)
        assertEquals(3, center.y)
        assertEquals(200 - 6, center.w)
        assertEquals(20 - 6, center.h)
    }

    @Test
    fun regionsTileTheFullSpriteWithoutGapsOrOverlap() {
        val area = SpriteSlicer.ninePatch(button).sumOf { it.w * it.h }
        assertEquals(200 * 20, area)
    }

    @Test
    fun everyRegionGetsADistinctCodepointInTheSliceRange() {
        val codepoints = SpriteSlicer.ninePatch(button).map { it.codepoint }
        assertEquals(9, codepoints.toSet().size)
        assertTrue(
            codepoints.all { it >= SpriteGlyphs.SLICE_BASE_CODEPOINT },
            "slices must not share the whole-sprite glyph range"
        )
    }

    @Test
    fun repeatedCallsReuseTheSameCodepointsRatherThanLeaking() {
        val first = SpriteSlicer.ninePatch(button).map { it.codepoint }
        val second = SpriteSlicer.ninePatch(button).map { it.codepoint }
        assertEquals(first, second)
    }

    @Test
    fun spritesWithoutNineSliceMetadataProduceNoRegions() {
        val plain = button.copy(nineSlice = null)
        assertEquals(0, SpriteSlicer.ninePatch(plain).size)
    }
}
