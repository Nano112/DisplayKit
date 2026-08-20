package io.schemat.displaykit.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SpriteIndexTest {

    private val fixture = """
        {
          "sourceVersion": "1.21.11",
          "sprites": [
            {
              "atlas": "gui", "sprite": "hud/hotbar",
              "width": 182, "height": 22,
              "texture": "minecraft:gui/sprites/hud/hotbar.png",
              "greyscale": false
            },
            {
              "atlas": "gui", "sprite": "widget/button",
              "width": 200, "height": 20,
              "texture": "minecraft:gui/sprites/widget/button.png",
              "greyscale": false,
              "nineSlice": { "left": 3, "top": 3, "right": 3, "bottom": 3, "stretchInner": false }
            },
            {
              "atlas": "blocks", "sprite": "block/fire_0",
              "width": 16, "height": 16,
              "texture": "minecraft:block/fire_0.png",
              "animated": true, "greyscale": false
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesEntriesAndVersion() {
        val index = SpriteIndex.loadFrom(fixture)
        assertEquals("1.21.11", index.sourceVersion)
        assertEquals(3, index.all().size)

        val hotbar = index.get(SpriteId("gui", "hud/hotbar"))!!
        assertEquals(182, hotbar.width)
        assertEquals(22, hotbar.height)
        assertEquals("minecraft:gui/sprites/hud/hotbar.png", hotbar.texture)
    }

    @Test
    fun animatedDefaultsToFalseAndIsReadWhenPresent() {
        val index = SpriteIndex.loadFrom(fixture)
        assertFalse(index.get(SpriteId("gui", "hud/hotbar"))!!.animated)
        assertTrue(index.get(SpriteId("blocks", "block/fire_0"))!!.animated)
    }

    @Test
    fun nineSliceIsNullWhenAbsentAndParsedWhenPresent() {
        val index = SpriteIndex.loadFrom(fixture)
        assertNull(index.get(SpriteId("gui", "hud/hotbar"))!!.nineSlice)

        val slice = index.get(SpriteId("gui", "widget/button"))!!.nineSlice!!
        assertEquals(3, slice.left)
        assertEquals(3, slice.bottom)
        assertFalse(slice.stretchInner)
    }

    @Test
    fun unknownSpriteReturnsNull() {
        assertNull(SpriteIndex.loadFrom(fixture).get(SpriteId("gui", "nope")))
    }

    @Test
    fun findMatchesAtlasOrSpriteSubstringCaseInsensitively() {
        val index = SpriteIndex.loadFrom(fixture)
        assertEquals(1, index.find("hotbar").size)
        assertEquals(2, index.find("GUI").size)
        assertEquals(0, index.find("zzz").size)
    }
}
