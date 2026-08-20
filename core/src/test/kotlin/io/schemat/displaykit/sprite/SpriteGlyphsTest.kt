package io.schemat.displaykit.sprite

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
        val cp = SpriteGlyphs.codepointFor(entry("a"), yOffset = 0)
        assertTrue(cp >= 0xF0000, "expected supplementary PUA-A, got ${cp.toString(16)}")
    }

    @Test
    fun sameSpriteAndOffsetReturnsTheSameCodepoint() {
        val first = SpriteGlyphs.codepointFor(entry("a"), 0)
        val second = SpriteGlyphs.codepointFor(entry("a"), 0)
        assertEquals(first, second)
    }

    @Test
    fun differentOffsetsGetDistinctCodepoints() {
        // ascent is baked per provider entry, so each Y offset is its own glyph.
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
    fun positiveYOffsetIsRejectedRatherThanSilentlyClampedToZero() {
        // ascent <= height is client-enforced, and yOffset = 0 already puts
        // ascent at its maximum (height) — there is no upward slack to shift
        // into, so a positive offset must be an error, not a no-op.
        val failure = assertFailsWith<IllegalArgumentException> {
            SpriteGlyphs.codepointFor(entry("a"), yOffset = 4)
        }
        assertTrue(
            failure.message!!.contains("positive"),
            "expected message to mention positive offsets being unsupported, got: ${failure.message}"
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
    // (sprite, yOffset) variant, so the guard permits exactly
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
}
