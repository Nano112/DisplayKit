package io.schemat.displaykit.pack

import com.google.gson.JsonParser
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpriteSolidProviderTest {

    private val button = SpriteEntry(
        id = SpriteId("gui", "widget/button"),
        width = 200, height = 20,
        texture = "minecraft:gui/sprites/widget/button.png"
    )

    @BeforeTest fun reset() = SpriteSolidProvider.clear()
    @AfterTest fun tearDown() = SpriteSolidProvider.clear()

    @Test
    fun registeringYieldsAStableCustomModelData() {
        val a = SpriteSolidProvider.register(button, thicknessPx = 1)
        val b = SpriteSolidProvider.register(button, thicknessPx = 1)
        assertEquals(a, b, "same sprite and thickness must reuse one model")
    }

    @Test
    fun differentThicknessesAreDifferentModels() {
        assertNotEquals(
            SpriteSolidProvider.register(button, 1),
            SpriteSolidProvider.register(button, 4)
        )
    }

    @Test
    fun theModelReferencesTheVanillaTexturePathSoNoImageShips() {
        SpriteSolidProvider.register(button, 1)
        val b = PackBuilder(PackConfig())
        SpriteSolidProvider.contributeAssets(b)
        assertEquals(0, b.imageCount(), "textured solids must ship no images")
        val name = SpriteSolidProvider.modelPathFor(button, 1)
        val json = JsonParser.parseString(b.capturedJson(name)).asJsonObject
        val textures = json.getAsJsonObject("textures")
        assertTrue(
            textures.entrySet().any { it.value.asString == "minecraft:gui/sprites/widget/button" },
            "expected the vanilla texture path, got $textures"
        )
    }

    @Test
    fun theModelIsASlabOfTheRequestedThickness() {
        SpriteSolidProvider.register(button, 3)
        val b = PackBuilder(PackConfig())
        SpriteSolidProvider.contributeAssets(b)
        val json = JsonParser.parseString(
            b.capturedJson(SpriteSolidProvider.modelPathFor(button, 3))
        ).asJsonObject
        val el = json.getAsJsonArray("elements")[0].asJsonObject
        val from = el.getAsJsonArray("from").map { it.asFloat }
        val to = el.getAsJsonArray("to").map { it.asFloat }
        assertEquals(3f, to[2] - from[2], "z extent should equal the thickness")
    }

    @Test
    fun nothingIsEmittedUntilSomethingIsRegistered() {
        val b = PackBuilder(PackConfig())
        SpriteSolidProvider.contributeAssets(b)
        assertEquals(0, b.imageCount())
    }
}
