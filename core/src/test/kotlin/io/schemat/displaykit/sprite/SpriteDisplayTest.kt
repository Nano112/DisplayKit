package io.schemat.displaykit.sprite

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.Billboard
import io.schemat.displaykit.render.DkColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpriteDisplayTest {

    private fun entry(w: Int, h: Int) = SpriteEntry(
        id = SpriteId("gui", "test"),
        width = w, height = h,
        texture = "minecraft:gui/test.png"
    )

    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertTrue(abs(expected - actual) < 1e-4f, "$what: expected $expected, got $actual")
    }

    @Test
    fun createsATransparentBackedSpriteDisplayAtBlockPixelScale() {
        val e = entry(64, 48)
        val display = SpriteDisplay.create(e, Vec3d(1.0, 2.0, 3.0), scale = 1f)

        assertEquals(Vec3d(1.0, 2.0, 3.0), display.position)
        assertEquals(DkColor.TRANSPARENT, display.backgroundColor)
        assertEquals(e.id.atlas, display.text.sprite?.atlas)
        assertEquals(e.id.sprite, display.text.sprite?.name)

        val m = display.transformation.toFloatArray()
        assertClose(20f, m[0], "scaleX")
        assertClose(15f, m[5], "scaleY")
        assertClose(-0.5f, m[12], "translationX")
        assertClose(-2.25f, m[13], "translationY")
    }

    @Test
    fun defaultsToFixedBillboardSoQuadsCanBeOriented() {
        // A sprite used as a block face must not turn to face the player.
        val display = SpriteDisplay.create(entry(16, 16), Vec3d.ZERO)
        assertEquals(Billboard.FIXED, display.billboard)
    }

    @Test
    fun billboardIsOverridable() {
        val display = SpriteDisplay.create(entry(16, 16), Vec3d.ZERO, billboard = Billboard.CENTER)
        assertEquals(Billboard.CENTER, display.billboard)
    }

    @Test
    fun applyToRescalesAnExistingDisplay() {
        val e = entry(16, 16)
        val display = SpriteDisplay.create(e, Vec3d.ZERO, scale = 1f)
        SpriteDisplay.applyTo(display, e, scale = 2f)

        val m = display.transformation.toFloatArray()
        assertClose(10f, m[0], "scaleX doubled")
        assertClose(10f, m[5], "scaleY doubled")
        assertClose(-0.25f, m[12], "translationX scales with it")
    }

    @Test
    fun shadowIsDisabledSoSpritesReadCleanly() {
        assertEquals(false, SpriteDisplay.create(entry(16, 16), Vec3d.ZERO).hasShadow)
    }
}
