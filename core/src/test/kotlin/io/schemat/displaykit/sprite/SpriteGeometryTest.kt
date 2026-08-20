package io.schemat.displaykit.sprite

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SpriteGeometryTest {

    private fun entry(w: Int, h: Int) = SpriteEntry(
        id = SpriteId("test", "sprite"),
        width = w, height = h,
        texture = "minecraft:test/sprite.png"
    )

    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertTrue(abs(expected - actual) < 1e-4f, "$what: expected $expected, got $actual")
    }

    @Test
    fun squareSpriteMatchesUpstreamWorkedExample() {
        // 16x16 -> scale [5,5,1], translation [-0.125,-0.75,0]
        val s = SpriteGeometry.scaleFor(entry(16, 16), 1f)
        assertClose(5f, s.x, "scaleX")
        assertClose(5f, s.y, "scaleY")
        assertClose(1f, s.z, "scaleZ")

        val t = SpriteGeometry.centeringTranslation(s)
        assertClose(-0.125f, t.x, "translationX")
        assertClose(-0.75f, t.y, "translationY")
        assertClose(0f, t.z, "translationZ")
    }

    @Test
    fun nonSquareSpriteUsesScaleTimesConstNotPixelsTimesConst() {
        // 64x48 -> scale [20,15,1], translation [-0.5,-2.25,0].
        // The upstream README claims [-1.6,-7.2,0] here; that is pixels x const
        // and contradicts both its own formula and its 16x16 example. The
        // generator's emitted commands confirm scale x const is correct.
        val s = SpriteGeometry.scaleFor(entry(64, 48), 1f)
        assertClose(20f, s.x, "scaleX")
        assertClose(15f, s.y, "scaleY")

        val t = SpriteGeometry.centeringTranslation(s)
        assertClose(-0.5f, t.x, "translationX")
        assertClose(-2.25f, t.y, "translationY")
    }

    @Test
    fun matchesGeneratedBannerCommand() {
        // browse.mcfunction bakes a 64x64 banner as
        // transformation:[20.0,0,0,-0.5,0,20.0,0,-3.0,...]
        val s = SpriteGeometry.scaleFor(entry(64, 64), 1f)
        val t = SpriteGeometry.centeringTranslation(s)
        assertClose(20f, s.x, "scaleX")
        assertClose(20f, s.y, "scaleY")
        assertClose(-0.5f, t.x, "translationX")
        assertClose(-3.0f, t.y, "translationY")
    }

    @Test
    fun scaleMultiplierIsLinear() {
        val single = SpriteGeometry.scaleFor(entry(16, 16), 1f)
        val double = SpriteGeometry.scaleFor(entry(16, 16), 2f)
        assertClose(single.x * 2f, double.x, "scaleX doubles")
        assertClose(single.y * 2f, double.y, "scaleY doubles")
    }

    @Test
    fun worldSizeIsOneBlockPixelPerSpritePixelAtScaleOne() {
        // A 16x16 sprite at scale 1.0 occupies exactly one block.
        val size = SpriteGeometry.worldSize(entry(16, 16), 1f)
        assertClose(1f, size.x, "world width")
        assertClose(1f, size.y, "world height")

        // A 64x48 sprite occupies 4x3 blocks.
        val big = SpriteGeometry.worldSize(entry(64, 48), 1f)
        assertClose(4f, big.x, "world width")
        assertClose(3f, big.y, "world height")
    }

    @Test
    fun transformDecomposesToTheSameScaleAndTranslation() {
        val e = entry(64, 48)
        val m = SpriteGeometry.transform(e, 1f).toFloatArray()
        // Column-major JOML: m[0]=scaleX, m[5]=scaleY, m[12..13]=translation
        assertClose(20f, m[0], "matrix scaleX")
        assertClose(15f, m[5], "matrix scaleY")
        assertClose(-0.5f, m[12], "matrix translationX")
        assertClose(-2.25f, m[13], "matrix translationY")
    }
}
