package io.schemat.displaykit.pack

import com.google.gson.JsonParser
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

    // A model alone is unreachable: without an item definition mapping the
    // CustomModelData onto it, the entity renders as a plain leather horse
    // armor and section C of the spec ships dead.

    @Test
    fun theItemDefinitionSelectsTheRegisteredCustomModelData() {
        val cmd = SpriteSolidProvider.register(button, 1)
        val b = PackBuilder(PackConfig())
        SpriteSolidProvider.contributeAssets(b)

        val def = JsonParser.parseString(
            b.capturedJson(SpriteSolidProvider.itemDefinitionPath())
        ).asJsonObject.getAsJsonObject("model")

        assertEquals("minecraft:select", def.get("type").asString)
        assertEquals("minecraft:custom_model_data", def.get("property").asString)

        val case = def.getAsJsonArray("cases").map { it.asJsonObject }
            .singleOrNull { it.get("when").asString == cmd.toString() }
        assertNotNull(case, "expected a case selecting CMD $cmd, got ${def.getAsJsonArray("cases")}")

        // and it must point at the model actually written to the pack
        val ref = case.getAsJsonObject("model").get("model").asString
        val expectedPath = "assets/${ref.replace(':', '/').replaceFirst("/item/", "/models/item/")}.json"
        assertEquals(SpriteSolidProvider.modelPathFor(button, 1), expectedPath)
        b.capturedJson(expectedPath)   // throws if the model was never emitted
    }

    @Test
    fun theItemDefinitionIsForTheItemSpriteSolidActuallySpawns() {
        assertEquals(
            "assets/minecraft/items/leather_horse_armor.json",
            SpriteSolidProvider.itemDefinitionPath()
        )
        assertEquals("minecraft:leather_horse_armor", SpriteSolidProvider.BASE_ITEM)
    }

    @Test
    fun casesFromAnotherProviderOnTheSameItemSurvive() {
        // ItemModelAssetProvider carries nucleation's models on this same base
        // item by default, so both write assets/minecraft/items/
        // leather_horse_armor.json. Plain addRaw would make that
        // last-writer-wins; addItemDefinition merges.
        val cmd = SpriteSolidProvider.register(button, 1)
        val b = PackBuilder(PackConfig())
        b.addItemDefinition(
            SpriteSolidProvider.itemDefinitionPath(),
            """{"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
               "fallback":{"type":"minecraft:model","model":"minecraft:item/leather_horse_armor"},
               "cases":[{"when":"1234","model":{"type":"minecraft:model","model":"nucleation:item/golem_head"}}]}}"""
        )
        SpriteSolidProvider.contributeAssets(b)

        val whens = JsonParser.parseString(
            b.capturedJson(SpriteSolidProvider.itemDefinitionPath())
        ).asJsonObject.getAsJsonObject("model").getAsJsonArray("cases")
            .map { it.asJsonObject.get("when").asString }

        assertTrue("1234" in whens, "the other provider's case must survive, got $whens")
        assertTrue(cmd.toString() in whens, "the solid's own case must survive, got $whens")
    }

    // SpriteSolidProvider and ModelManager.calculateNextCustomModelData both
    // allocate CustomModelData on leather_horse_armor. ModelManager grows from
    // 1000 in 100-blocks with model count, so a low base collides eventually.
    // Follow SpriteGlyphs' precedent: bound the range and fail loudly, naming
    // both allocators, rather than silently handing out a value twice.

    @Test
    fun theCustomModelDataRangeClearsModelManagersByAWideMargin() {
        assertTrue(
            SpriteSolidProvider.BASE_CMD >= 100_000,
            "ModelManager starts at 1000 and steps 100 per model; a base of " +
                "${SpriteSolidProvider.BASE_CMD} leaves too little headroom"
        )
        assertTrue(SpriteSolidProvider.MAX_CMD > SpriteSolidProvider.BASE_CMD)
    }

    @Test
    fun exceedingTheRangeFailsLoudlyNamingBothAllocators() {
        SpriteSolidProvider.exhaustForTest()
        val e = assertFailsWith<IllegalStateException> {
            SpriteSolidProvider.register(button, 1)
        }
        assertTrue(
            e.message!!.contains("SpriteSolidProvider"),
            "the message must name this allocator, got: ${e.message}"
        )
        assertTrue(
            e.message!!.contains("ModelManager"),
            "the message must name the allocator it could collide with, got: ${e.message}"
        )
    }

    @Test
    fun theGuardDoesNotFireForAlreadyRegisteredModels() {
        val cmd = SpriteSolidProvider.register(button, 1)
        SpriteSolidProvider.exhaustForTest()
        assertEquals(cmd, SpriteSolidProvider.register(button, 1),
            "a known key is a map lookup and must not consult the allocator")
    }
}
