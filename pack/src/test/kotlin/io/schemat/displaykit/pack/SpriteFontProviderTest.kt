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
    fun positiveYOffsetIsRejectedNotClamped() {
        // The client throws "Ascent {} higher than height {}" if ascent ever
        // exceeds height. yOffset = 0 already puts ascent at its maximum, so a
        // positive offset must be a hard error, not silently clamped to zero
        // (which would waste a glyph slot on a byte-identical provider).
        val failure = assertFailsWith<IllegalArgumentException> {
            SpriteGlyphs.request(entry("small", 8, 8), 4)
        }
        assertTrue(
            failure.message!!.contains("positive"),
            "expected message to mention positive offsets being unsupported, got: ${failure.message}"
        )
    }

    @Test
    fun ascentEqualsHeightAtZeroOffsetAndIsStrictlyLessBelowIt() {
        // Discriminates an off-by-one or sign flip in the ascent arithmetic:
        // ascent must be exactly height at yOffset = 0, and strictly less than
        // height for any negative yOffset, over more than one sprite height.
        for (h in listOf(8, 22)) {
            val e = entry("h$h", w = h, h = h)
            SpriteGlyphs.request(e, 0)
            SpriteGlyphs.request(e, -3)
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
                "ascent ${shifted.get("ascent")} must be strictly less than height ${shifted.get("height")} for a negative offset"
            )
            assertEquals(h - 3, shifted.get("ascent").asInt)
        }
    }

    @Test
    fun negativeYOffsetLowersAscent() {
        val e = entry("shifty", 16, 16)
        SpriteGlyphs.request(e, 0)
        SpriteGlyphs.request(e, -4)
        val providers = JsonParser.parseString(fontJson())
            .asJsonObject.getAsJsonArray("providers")
        val ascents = (0 until providers.size())
            .map { providers[it].asJsonObject.get("ascent").asInt }
        assertEquals(ascents[0] - 4, ascents[1])
    }

    @Test
    fun writesNoImagesIntoThePack() {
        SpriteGlyphs.request(entry("hud/hotbar", 182, 22), 0)
        val builder = PackBuilder(PackConfig())
        SpriteFontProvider.contributeAssets(builder)
        assertEquals(0, builder.imageCount(), "by-reference glyphs must ship zero textures")
    }
}
