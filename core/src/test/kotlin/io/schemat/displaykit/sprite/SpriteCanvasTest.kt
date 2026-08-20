package io.schemat.displaykit.sprite

import io.schemat.displaykit.render.DkColor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpriteCanvasTest {

    private fun entry(name: String, w: Int = 8, h: Int = 8, animated: Boolean = false) =
        SpriteEntry(
            id = SpriteId("gui", name),
            width = w, height = h,
            texture = "minecraft:gui/$name.png",
            greyscale = true,
            animated = animated
        )

    @BeforeTest fun reset() = SpriteGlyphs.clear()
    @AfterTest fun tearDown() = SpriteGlyphs.clear()

    @Test
    fun emptyCanvasProducesAnEmptyComponent() {
        val c = SpriteCanvas(64, 64)
        assertEquals("", c.toTextComponent().plain())
    }

    @Test
    fun everythingLandsInASingleComponentTree() {
        // The whole point: N sprites, one entity.
        val c = SpriteCanvas(64, 64)
        repeat(20) { i -> c.draw(entry("cell$i"), x = i, y = 0) }
        val component = c.toTextComponent()
        assertTrue(component.children.isNotEmpty())
        assertEquals(20, SpriteGlyphs.requested().size)
    }

    @Test
    fun distinctYOffsetsAllocateDistinctGlyphVariants() {
        val c = SpriteCanvas(64, 64)
        val e = entry("dot")
        c.draw(e, x = 0, y = 0)
        c.draw(e, x = 0, y = 8)
        assertEquals(2, SpriteGlyphs.requested().size)
    }

    @Test
    fun sameCellDrawnTwiceReusesOneGlyphVariant() {
        val c = SpriteCanvas(64, 64)
        val e = entry("dot")
        c.draw(e, x = 0, y = 0)
        c.draw(e, x = 16, y = 0)
        assertEquals(1, SpriteGlyphs.requested().size)
    }

    @Test
    fun tintIsCarriedOntoTheDrawnChild() {
        val c = SpriteCanvas(64, 64)
        c.draw(entry("dot"), 0, 0, tint = DkColor.fromRGB(0, 255, 136))
        val tinted = c.toTextComponent().children.firstOrNull { it.color != null }
        assertEquals(DkColor.fromRGB(0, 255, 136), tinted?.color)
    }

    @Test
    fun animatedSpritesAreRejected() {
        val c = SpriteCanvas(64, 64)
        assertFailsWith<IllegalArgumentException> {
            c.draw(entry("fire", animated = true), 0, 0)
        }
    }

    @Test
    fun clearResetsTheCanvasButNotTheGlyphRegistry() {
        val c = SpriteCanvas(64, 64)
        c.draw(entry("dot"), 0, 0)
        c.clear()
        assertEquals("", c.toTextComponent().plain())
    }

    @Test
    fun advanceDecomposesAnyIntegerIntoAvailableSpacingChars() {
        // spacing.json ships powers of two, 1..128, positive and negative.
        assertEquals("", Spacing.advance(0))
        assertTrue(Spacing.advance(7).isNotEmpty())
        assertTrue(Spacing.advance(-13).isNotEmpty())
        assertTrue(Spacing.advance(255).isNotEmpty())
    }

    // --- Added: the respecified cursor logic is load-bearing, and none of
    // the tests above actually check pixel positioning. ---

    @Test
    fun touchingSpritesEmitANegativeSpacingCorrection() {
        // Two 16px-wide sprites drawn back to back (0 and 16). A glyph's
        // advance is width + 1 = 17, so the gap to x=16 is 16 - 17 = -1.
        // This is the single test that would catch a cursor that only ever
        // advances by one pixel per item.
        val c = SpriteCanvas(64, 64)
        val e = entry("wide", w = 16, h = 16)
        c.draw(e, x = 0, y = 0)
        c.draw(e, x = 16, y = 0)

        val spacingChildren = c.toTextComponent().children.filter { it.font == Spacing.FONT_ID }
        assertEquals(1, spacingChildren.size)
        assertEquals(Spacing.advance(-1), spacingChildren.single().text)
    }

    @Test
    fun gapBeforeAFreestandingSpriteEmitsALeadingAdvance() {
        // A sprite at x=40 with nothing before it needs a +40 advance first.
        val c = SpriteCanvas(64, 64)
        c.draw(entry("solo"), x = 40, y = 0)

        val children = c.toTextComponent().children
        assertEquals(2, children.size)
        assertEquals(Spacing.FONT_ID, children.first().font)
        assertEquals(Spacing.advance(40), children.first().text)
    }

    @Test
    fun advanceCharsSumBackToTheRequestedPixelValue() {
        // A string that is merely non-empty proves nothing about correctness
        // — decode every returned character back to its pixel value via the
        // same table spacing.json declares, and confirm the sum round-trips.
        val negative = mapOf(
            '\uF001' to -1, '\uF002' to -2, '\uF004' to -4, '\uF008' to -8,
            '\uF010' to -16, '\uF020' to -32, '\uF040' to -64, '\uF080' to -128
        )
        val positive = mapOf(
            '\uF101' to 1, '\uF102' to 2, '\uF104' to 4, '\uF108' to 8,
            '\uF110' to 16, '\uF120' to 32, '\uF140' to 64, '\uF180' to 128
        )
        val table = negative + positive

        for (px in listOf(0, 7, -13, 255, -255)) {
            val chars = Spacing.advance(px)
            val sum = chars.sumOf { c -> table.getValue(c) }
            assertEquals(px, sum, "Spacing.advance($px) decoded to $sum")
        }
    }

    @Test
    fun drawWithNegativeYThrows() {
        val c = SpriteCanvas(64, 64)
        assertFailsWith<IllegalArgumentException> {
            c.draw(entry("dot"), x = 0, y = -1)
        }
    }
}
