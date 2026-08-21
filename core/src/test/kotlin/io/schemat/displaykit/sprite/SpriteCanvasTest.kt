package io.schemat.displaykit.sprite

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
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

    // --- Row model (text() now honours y, quantised to TextMetrics.LINE_HEIGHT_PX) ---

    @Test
    fun itemsOnDifferentRowsAreSeparatedByANewline() {
        val c = SpriteCanvas(64, 64)
        c.text("a", x = 0, y = 0)
        c.text("b", x = 0, y = 10) // row 1
        val children = c.toTextComponent().children
        assertEquals(listOf("a", "\n", "b"), children.map { it.text })
    }

    @Test
    fun emptyIntermediateRowsStillEmitNewlines() {
        // Items in rows 0 and 3 (y=30): rows 1 and 2 have no items but must
        // still each contribute a newline, or row 3's vertical position
        // collapses upward.
        val c = SpriteCanvas(64, 64)
        c.text("a", x = 0, y = 0)
        c.text("b", x = 0, y = 30)
        val children = c.toTextComponent().children
        assertEquals(listOf("a", "\n", "\n", "\n", "b"), children.map { it.text })
    }

    @Test
    fun cursorXResetsAtTheStartOfEachRow() {
        // Row 0 ends with a large cursorX. Row 1 places an item at x=0 —
        // if the cursor carried over, that would require a large negative
        // spacing correction; it must instead emit no leading advance at all.
        val c = SpriteCanvas(64, 64)
        c.text("a very long line of text indeed", x = 0, y = 0)
        c.text("row1", x = 0, y = 10)

        val allChildren = c.toTextComponent().children
        val newlineIndex = allChildren.indexOfFirst { it.text == "\n" }
        val row1Children = allChildren.subList(newlineIndex + 1, allChildren.size)

        assertEquals(1, row1Children.size)
        assertEquals("row1", row1Children.single().text)
    }

    @Test
    fun aSixteenPxGlyphAtY30ProducesAnAscentPuttingItsTopAtExactlyY30() {
        // y=30 -> natural row 3 (origin 30, at the renderer's line pitch of
        // 10). Required ascent = 30 + GLYPH_TOP_BEARING_PX(7) - 30 = 7, which
        // a 16px-tall glyph satisfies on its natural row (no fallback). A
        // glyph's top, relative to its line's origin, is
        // GLYPH_TOP_BEARING_PX - ascent, so its absolute top is
        // 30 + 7 - 7 = 30 -- pixel-exact.
        //
        // (Superseded a test that asserted yOffset == -7 for y=16 under the
        // OLD, buggy sign convention -- see TextMetrics.GLYPH_TOP_BEARING_PX.)
        val c = SpriteCanvas(64, 64)
        c.draw(entry("tall", w = 16, h = 16), x = 0, y = 30)
        val variant = SpriteGlyphs.requested().single()
        assertEquals(7, variant.ascent)
    }

    @Test
    fun anEightyTwoPxCropAtY9LandsAtExactlyY9OnItsNaturalRow() {
        // y=9 -> natural row 0 (origin 0, at the renderer's line pitch of
        // 10). Required ascent = 0 + 7 - 9 = -2; a negative ascent is legal
        // (only ascent > height is rejected), so no row fallback is needed. Exercised through drawGlyph (the nine-slice
        // path) rather than draw(), since whole sprites this tall don't occur
        // in practice.
        val c = SpriteCanvas(400, 100)
        var seenAscent: Int? = null
        c.drawGlyph(x = 0, y = 9, height = 82, advanceWidth = 83) { ascent ->
            seenAscent = ascent
            "x"
        }
        assertEquals(-2, seenAscent)
    }

    @Test
    fun aOnePxCropTooShortForItsNaturalRowFallsBackToAnEarlierRowAtTheRightY() {
        // y=9 -> natural row 1 needs ascent 7, illegal for a 1px-tall glyph
        // (ascent <= height). GlyphPlacement falls back to row 0, where the
        // required ascent is 7 - 9 = -2 (legal: any negative ascent is fine).
        // Row 0's origin is 0, so the glyph's absolute top is still
        // 0 + 7 - (-2) = 9 -- the fallback changes WHICH row emits it, not
        // where it lands.
        val c = SpriteCanvas(400, 100)
        var seenAscent: Int? = null
        c.drawGlyph(x = 0, y = 9, height = 1, advanceWidth = 2) { ascent ->
            seenAscent = ascent
            "x"
        }
        assertEquals(-2, seenAscent)
    }

    @Test
    fun aCaseNoRowCanSatisfyWarnsOnceAndDrawsNothing() {
        // y=0 -> natural row 0 needs ascent 7, illegal for a 1px-tall glyph,
        // and row 0 is already the floor -- no earlier row to fall back to.
        SpriteDiagnostics.reset()
        val c = SpriteCanvas(64, 64)
        var resolveCalled = false
        c.drawGlyph(x = 0, y = 0, height = 1, advanceWidth = 2) { resolveCalled = true; "x" }

        assertEquals(0, c.itemCount(), "an unsatisfiable placement must draw nothing")
        assertTrue(!resolveCalled, "resolve must not be called with an illegal ascent")
        assertTrue(SpriteDiagnostics.warnings().isNotEmpty(), "an unsatisfiable placement must warn")
        SpriteDiagnostics.reset()
    }

    // --- requiresPack(): plain text needs no pack; a non-zero gap does ---

    @Test
    fun textOnlyCanvasAtNonZeroXRequiresPackBecauseOfTheLeadingSpacingChar() {
        val c = SpriteCanvas(64, 64)
        c.text("hi", x = 10, y = 0)
        assertTrue(c.requiresPack())
    }

    @Test
    fun textOnlyCanvasAtOriginNeedsNoPackAtAll() {
        val c = SpriteCanvas(64, 64)
        c.text("hi", x = 0, y = 0)
        assertTrue(!c.requiresPack())
    }

    // --- maxRowAdvance(): the widest row's end cursor, shared with the walk
    // toTextComponent() actually emits so it cannot drift ---

    @Test
    fun maxRowAdvanceIsZeroForAnEmptyCanvas() {
        val c = SpriteCanvas(64, 64)
        assertEquals(0, c.maxRowAdvance())
    }

    @Test
    fun maxRowAdvanceReturnsTheWidestRowsEndCursor() {
        val c = SpriteCanvas(600, 64)
        c.text("short", x = 0, y = 0)
        val longLine = "a much longer line of text than the other row by far"
        c.text(longLine, x = 0, y = 9) // row 1 under FONT_LINE_HEIGHT_PX = 9
        assertEquals(TextMetrics.textWidthPx(longLine), c.maxRowAdvance())
    }
}
