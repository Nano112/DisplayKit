package io.schemat.displaykit.pack

import com.google.gson.JsonParser
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun ascentNeverExceedsHeight() {
        // The client throws "Ascent {} higher than height {}" otherwise.
        SpriteGlyphs.request(entry("small", 8, 8), 0)
        val p = JsonParser.parseString(fontJson())
            .asJsonObject.getAsJsonArray("providers")[0].asJsonObject
        assertTrue(
            p.get("ascent").asInt <= p.get("height").asInt,
            "ascent ${p.get("ascent")} must be <= height ${p.get("height")}"
        )
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
