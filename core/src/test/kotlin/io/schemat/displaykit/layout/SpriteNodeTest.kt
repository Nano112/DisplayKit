package io.schemat.displaykit.layout

import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpriteNodeTest {

    private fun entry(w: Int, h: Int) = SpriteEntry(
        id = SpriteId("gui", "test"),
        width = w, height = h,
        texture = "minecraft:gui/test.png"
    )

    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertTrue(abs(expected - actual) < 1e-4f, "$what: expected $expected, got $actual")
    }

    @Test
    fun intrinsicSizeComesFromTheSpritesTrueDimensions() {
        // 64x48 at scale 1 is 4x3 blocks.
        val node = SpriteNode("s", entry(64, 48), scale = 1f)
        val size = node.measure(Constraints.Unbounded)
        assertClose(4f, size.width, "width")
        assertClose(3f, size.height, "height")
    }

    @Test
    fun scaleMultipliesTheIntrinsicSize() {
        val node = SpriteNode("s", entry(16, 16), scale = 2f)
        val size = node.measure(Constraints.Unbounded)
        assertClose(2f, size.width, "width")
        assertClose(2f, size.height, "height")
    }

    @Test
    fun fixedConstraintsWin() {
        val node = SpriteNode("s", entry(64, 48), scale = 1f)
        val size = node.measure(Constraints.fixed(1f, 1f))
        assertClose(1f, size.width, "width")
        assertClose(1f, size.height, "height")
    }

    @Test
    fun intrinsicSizeIsClampedToMaxConstraints() {
        val node = SpriteNode("s", entry(64, 48), scale = 1f)
        val size = node.measure(Constraints.maxSize(2f, 2f))
        assertTrue(size.width <= 2f, "width clamped")
        assertTrue(size.height <= 2f, "height clamped")
    }

    @Test
    fun measureReflectsALaterEntryChange() {
        val node = SpriteNode("s", entry(16, 16), scale = 1f)
        node.entry = entry(64, 48)
        val size = node.measure(Constraints.Unbounded)
        assertClose(4f, size.width, "width after change")
    }

    @Test
    fun cannotHaveChildren() {
        val node = SpriteNode("s", entry(16, 16))
        assertFailsWith<UnsupportedOperationException> {
            node.addChild(SpriteNode("child", entry(16, 16)))
        }
    }
}
