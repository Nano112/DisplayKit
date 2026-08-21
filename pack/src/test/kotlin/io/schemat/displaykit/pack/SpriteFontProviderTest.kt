package io.schemat.displaykit.pack

import com.google.gson.JsonParser
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpriteFontProviderTest {

    private fun entry(name: String, w: Int, h: Int) = SpriteEntry(
        id = SpriteId("gui", name),
        width = w, height = h,
        texture = "minecraft:gui/sprites/$name.png"
    )

    @BeforeTest fun reset() = SpriteGlyphs.clear()
    @AfterTest fun tearDown() = SpriteGlyphs.clear()

    private fun fontJson(): String {
        val builder = PackBuilder(PackConfig())
        SpriteFontProvider.contributeAssets(builder)
        return builder.capturedJson("assets/displaykit/font/sprites.json")
    }

    @Test
    fun emitsOneProviderPerRequestedVariantReferencingTheVanillaTexture() {
        SpriteGlyphs.request(entry("hud/hotbar", 182, 22), 0)
        val providers = JsonParser.parseString(fontJson())
            .asJsonObject.getAsJsonArray("providers")

        assertEquals(1, providers.size())
        val p = providers[0].asJsonObject
        assertEquals("bitmap", p.get("type").asString)
        assertEquals("minecraft:gui/sprites/hud/hotbar.png", p.get("file").asString)
    }

    @Test
    fun heightIsTheTrueSpriteHeightSoAspectRatioIsPreserved() {
        SpriteGlyphs.request(entry("hud/hotbar", 182, 22), 0)
        val p = JsonParser.parseString(fontJson())
            .asJsonObject.getAsJsonArray("providers")[0].asJsonObject
        assertEquals(22, p.get("height").asInt)
    }

    @Test
    fun anAscentAboveHeightIsRejectedNotClamped() {
        // The client throws "Ascent {} higher than height {}" if ascent ever
        // exceeds height. entry("small", 8, 8) is 8px tall, so requesting an
        // ascent of 9 must be a hard error, not silently clamped to 8 (which
        // would waste a glyph slot on a byte-identical provider).
        //
        // (Superseded a test that rejected ANY positive yOffset under the
        // OLD API, where the caller passed a height-relative offset rather
        // than the ascent itself — under the new ascent <= height rule, a
        // small positive value like the old test's yOffset=4 is legal.)
        val failure = assertFailsWith<IllegalArgumentException> {
            SpriteGlyphs.request(entry("small", 8, 8), ascent = 9)
        }
        assertTrue(
            failure.message!!.contains("exceeds") && failure.message!!.contains("height"),
            "expected message to explain that ascent exceeds height, got: ${failure.message}"
        )
    }

    @Test
    fun ascentEqualsHeightAtItsMaximumAndIsStrictlyLessWhenLowered() {
        // Discriminates an off-by-one or sign flip in the ascent arithmetic:
        // ascent must be exactly height at its maximum, and strictly less
        // than height when lowered by 3, over more than one sprite height.
        //
        // (Arithmetic only changed from the OLD version of this test: calls
        // now pass the ascent directly -- request(e, h) / request(e, h - 3)
        // -- instead of a height-relative yOffset the provider used to
        // compute into an ascent. The expected numbers are identical.)
        for (h in listOf(8, 22)) {
            val e = entry("h$h", w = h, h = h)
            SpriteGlyphs.request(e, ascent = h)
            SpriteGlyphs.request(e, ascent = h - 3)
        }
        val providers = JsonParser.parseString(fontJson())
            .asJsonObject.getAsJsonArray("providers")
        val byFile = (0 until providers.size())
            .map { providers[it].asJsonObject }
            .groupBy { it.get("file").asString }

        for (h in listOf(8, 22)) {
            val ps = byFile.getValue("minecraft:gui/sprites/h$h.png")
            val flat = ps.first { it.get("ascent").asInt == h }
            assertEquals(h, flat.get("height").asInt)

            val shifted = ps.first { it !== flat }
            assertEquals(h, shifted.get("height").asInt)
            assertTrue(
                shifted.get("ascent").asInt < shifted.get("height").asInt,
                "ascent ${shifted.get("ascent")} must be strictly less than height ${shifted.get("height")} when lowered"
            )
            assertEquals(h - 3, shifted.get("ascent").asInt)
        }
    }

    @Test
    fun ascentInTheEmittedJsonMatchesExactlyWhatWasRequested() {
        // Under the OLD API the provider computed ascent = height + yOffset
        // itself, and "negativeYOffsetLowersAscent" exercised that
        // computation. Placement (deriving an ascent from a target canvas Y)
        // now lives entirely in GlyphPlacement/SpriteCanvas -- this provider
        // just bakes whatever ascent the variant carries, unchanged, into the
        // JSON. So the meaningful thing left to test here is pass-through
        // fidelity, not a height-relative computation: the OLD test's
        // assertion (ascents[0] - 4 == ascents[1]) would still trivially
        // pass under the new code even if the provider ignored its variant
        // entirely, because it never checked the ascents against anything
        // but each other -- an instance of exactly the "silently stops
        // exercising its subject" trap.
        val e = entry("shifty", 16, 16)
        SpriteGlyphs.request(e, ascent = 16)
        SpriteGlyphs.request(e, ascent = -20)
        val providers = JsonParser.parseString(fontJson())
            .asJsonObject.getAsJsonArray("providers")
        val ascents = (0 until providers.size())
            .map { providers[it].asJsonObject.get("ascent").asInt }
            .toSet()
        assertEquals(setOf(16, -20), ascents)
    }

    @Test
    fun writesNoImagesIntoThePack() {
        SpriteGlyphs.request(entry("hud/hotbar", 182, 22), 0)
        val builder = PackBuilder(PackConfig())
        SpriteFontProvider.contributeAssets(builder)
        assertEquals(0, builder.imageCount(), "by-reference glyphs must ship zero textures")
    }
}
