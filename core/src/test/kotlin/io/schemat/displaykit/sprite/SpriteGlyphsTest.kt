package io.schemat.displaykit.sprite

import io.schemat.displaykit.render.TextMetrics
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpriteGlyphsTest {

    private fun entry(name: String, animated: Boolean = false) = SpriteEntry(
        id = SpriteId("gui", name),
        width = 16, height = 16,
        texture = "minecraft:gui/$name.png",
        animated = animated
    )

    @BeforeTest fun reset() = SpriteGlyphs.clear()
    @AfterTest fun tearDown() = SpriteGlyphs.clear()

    @Test
    fun allocatesFromSupplementaryPuaToAvoidCollidingWithExistingIcons() {
        // SpriteAssetProvider uses U+E000 upward; spacing.json uses U+F001-U+F200.
        val cp = SpriteGlyphs.codepointFor(entry("a"), ascent = 0)
        assertTrue(cp >= 0xF0000, "expected supplementary PUA-A, got ${cp.toString(16)}")
    }

    @Test
    fun sameSpriteAndAscentReturnsTheSameCodepoint() {
        val first = SpriteGlyphs.codepointFor(entry("a"), 0)
        val second = SpriteGlyphs.codepointFor(entry("a"), 0)
        assertEquals(first, second)
    }

    @Test
    fun differentAscentsGetDistinctCodepoints() {
        // ascent is baked per provider entry, so each ascent is its own glyph.
        val flat = SpriteGlyphs.codepointFor(entry("a"), 0)
        val shifted = SpriteGlyphs.codepointFor(entry("a"), -4)
        assertNotEquals(flat, shifted)
    }

    @Test
    fun differentSpritesGetDistinctCodepoints() {
        assertNotEquals(
            SpriteGlyphs.codepointFor(entry("a"), 0),
            SpriteGlyphs.codepointFor(entry("b"), 0)
        )
    }

    @Test
    fun animatedSpritesAreRejectedRatherThanRenderingTheWholeStrip() {
        val e = entry("fire", animated = true)
        val failure = assertFailsWith<IllegalArgumentException> {
            SpriteGlyphs.codepointFor(e, 0)
        }
        assertTrue(failure.message!!.contains("animated"))
    }

    @Test
    fun anAscentAboveHeightIsRejectedRatherThanSilentlyClamped() {
        // ascent <= height is client-enforced ("Ascent {} higher than height
        // {}", which fails the WHOLE font file). entry("a") is 16 tall, so an
        // ascent of 20 must be a hard error, not silently clamped to 16
        // (which would waste a glyph slot on a byte-identical provider).
        val failure = assertFailsWith<IllegalArgumentException> {
            SpriteGlyphs.codepointFor(entry("a"), ascent = 20)
        }
        assertTrue(
            failure.message!!.contains("exceeds") && failure.message!!.contains("height"),
            "expected message to explain that ascent exceeds height, got: ${failure.message}"
        )
    }

    @Test
    fun charsForRoundTripsThroughSurrogatePairs() {
        val e = entry("a")
        val cp = SpriteGlyphs.codepointFor(e, 0)
        val s = SpriteGlyphs.charsFor(e, 0)
        assertEquals(2, s.length, "supplementary codepoints need a surrogate pair")
        assertEquals(cp, s.codePointAt(0))
    }

    @Test
    fun charsForDefaultsToAscentEqualsHeight() {
        // The natural, non-canvas-clipped rendering (used directly in chat
        // text rather than through SpriteCanvas) is the maximum legal ascent.
        val e = entry("a")
        val cp = SpriteGlyphs.codepointFor(e)
        assertEquals(cp, SpriteGlyphs.codepointFor(e, ascent = e.height))
    }

    @Test
    fun requestedTracksEveryDistinctVariant() {
        SpriteGlyphs.request(entry("a"), 0)
        SpriteGlyphs.request(entry("a"), 0)
        SpriteGlyphs.request(entry("a"), -3)
        SpriteGlyphs.request(entry("b"), 0)
        assertEquals(3, SpriteGlyphs.requested().size)
    }

    // codepointFor's guard is `check(next < SLICE_BASE_CODEPOINT)`, evaluated
    // before each allocation and before `next` is incremented. Allocation
    // starts at BASE_CODEPOINT and advances by exactly 1 per distinct
    // (sprite, ascent) variant, so the guard permits exactly
    // (SLICE_BASE_CODEPOINT - BASE_CODEPOINT) whole-sprite allocations before
    // it throws. These tests prove that boundary arithmetically rather than
    // by actually allocating 32768 glyphs.

    @Test
    fun wholeSpriteGlyphCapacityIsExactlyTheGapBeforeSlices() {
        val capacity = SpriteGlyphs.SLICE_BASE_CODEPOINT - SpriteGlyphs.BASE_CODEPOINT
        assertEquals(
            32768, capacity,
            "whole-sprite glyph capacity must exactly match the distance to SLICE_BASE_CODEPOINT"
        )
    }

    @Test
    fun theFirstOverCapacityCodepointWouldLandExactlyOnSliceBaseCodepoint() {
        // Without the `next < SLICE_BASE_CODEPOINT` guard, the
        // (capacity + 1)-th whole-sprite allocation would be assigned
        // BASE_CODEPOINT + capacity — i.e. SLICE_BASE_CODEPOINT itself, the
        // first codepoint slices use. The guard exists precisely to reject
        // this allocation instead of silently colliding.
        val capacity = SpriteGlyphs.SLICE_BASE_CODEPOINT - SpriteGlyphs.BASE_CODEPOINT
        assertEquals(
            SpriteGlyphs.SLICE_BASE_CODEPOINT,
            SpriteGlyphs.BASE_CODEPOINT + capacity,
            "the first over-capacity codepoint must equal SLICE_BASE_CODEPOINT exactly"
        )
    }

    @Test
    fun sliceBaseCodepointLeavesRoomBeforeMaxCodepoint() {
        assertTrue(
            SpriteGlyphs.SLICE_BASE_CODEPOINT <= SpriteGlyphs.MAX_CODEPOINT,
            "the slice range must itself fit inside Supplementary PUA-A"
        )
    }

    @Test
    fun `warming all phases covers every y a sprite can be drawn at`() {
        val e = entry("filler")
        SpriteGlyphs.warmAllPhases(e)
        val warmed = SpriteGlyphs.requested().size

        // A fill tiles at whatever y its rect lands on, and ascent is baked
        // per y -- so an unwarmed fill mints a glyph the first time a panel
        // sits on a new phase. That is exactly how clicking a picker tab
        // rebuilt the resource pack, costing every connected client a
        // re-download. After warming, no y may allocate anything new.
        for (y in 0..200) {
            GlyphPlacement.resolve(y, e.height)?.let {
                SpriteGlyphs.request(e, it.ascent, e.height)
            }
        }

        assertEquals(warmed, SpriteGlyphs.requested().size)
    }

    @Test
    fun `warming all phases is bounded, not one variant per pixel`() {
        val e = entry("filler")
        SpriteGlyphs.warmAllPhases(e)

        // Ascent depends only on y modulo the line pitch, so the whole space
        // is a handful of variants. If this ever grows with the canvas, the
        // warm has become the leak it exists to prevent.
        assertTrue(
            SpriteGlyphs.requested().size <= 2 * TextMetrics.FONT_LINE_HEIGHT_PX,
            "warmAllPhases allocated ${SpriteGlyphs.requested().size} variants"
        )
    }
}
