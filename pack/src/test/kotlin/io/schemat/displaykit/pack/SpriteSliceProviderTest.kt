package io.schemat.displaykit.pack

import com.google.gson.JsonParser
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpriteSliceProviderTest {

    private val button = SpriteId("gui", "widget/button")
    private val tab = SpriteId("gui", "widget/tab")

    @BeforeTest fun reset() { SpriteGlyphs.clear(); SpriteSliceProvider.clear() }
    @AfterTest fun tearDown() { SpriteGlyphs.clear(); SpriteSliceProvider.clear() }

    @Test
    fun catalogLoadsTheCommittedManifest() {
        val crops = SliceCatalog.regionsFor(button)
        assertNotNull(crops, "widget/button must be in the committed manifest")
        assertEquals(9, crops.size)
    }

    @Test
    fun degenerateRegionsAreAbsentFromTheCatalog() {
        val crops = SliceCatalog.regionsFor(tab)
        assertNotNull(crops)
        assertEquals(6, crops.size, "tab has bottom=0, so no bottom row")
    }

    @Test
    fun aSpriteWithNoNineSliceIsNotInTheCatalog() {
        assertNull(SliceCatalog.regionsFor(SpriteId("gui", "hud/hotbar")))
    }

    @Test
    fun everyCatalogCropResolvesToAPackagedResource() {
        for (c in SliceCatalog.regionsFor(button)!!) {
            val stream = SliceCatalog::class.java.getResourceAsStream("/displaykit/slices/${c.resource}")
            assertNotNull(stream, "missing packaged crop ${c.resource}")
            stream.close()
        }
    }

    @Test
    fun nothingIsEmittedUntilRequested() {
        val b = PackBuilder(PackConfig())
        SpriteSliceProvider.contributeAssets(b)
        assertEquals(0, b.imageCount(), "unrequested slices must not ship")
    }

    @Test
    fun requestingASpriteEmitsItsCropsAndAFontProvider() {
        SpriteSliceProvider.request(button)
        val b = PackBuilder(PackConfig())
        SpriteSliceProvider.contributeAssets(b)
        assertEquals(9, b.imageCount())
        val json = JsonParser.parseString(
            b.capturedJson("assets/displaykit/font/sprite_slices.json")
        ).asJsonObject
        assertEquals(9, json.getAsJsonArray("providers").size())
    }

    @Test
    fun sliceCodepointsComeFromTheSliceRange() {
        SpriteSliceProvider.request(button)
        for (c in SliceCatalog.regionsFor(button)!!) {
            val cp = SpriteSliceProvider.codepointFor(button, c.x, c.y)
            assertNotNull(cp)
            assertTrue(cp >= SpriteGlyphs.SLICE_BASE_CODEPOINT,
                "slice codepoints must not collide with whole-sprite glyphs")
        }
    }

    @Test
    fun requestingTwiceReusesTheSameCodepoints() {
        SpriteSliceProvider.request(button)
        val first = SliceCatalog.regionsFor(button)!!.map { SpriteSliceProvider.codepointFor(button, it.x, it.y) }
        SpriteSliceProvider.request(button)
        val second = SliceCatalog.regionsFor(button)!!.map { SpriteSliceProvider.codepointFor(button, it.x, it.y) }
        assertEquals(first, second)
    }

    @Test
    fun eachProviderAscentEqualsItsCropHeight() {
        SpriteSliceProvider.request(button)
        val b = PackBuilder(PackConfig())
        SpriteSliceProvider.contributeAssets(b)
        val providers = JsonParser.parseString(
            b.capturedJson("assets/displaykit/font/sprite_slices.json")
        ).asJsonObject.getAsJsonArray("providers")
        for (p in providers) {
            val o = p.asJsonObject
            assertEquals(o.get("height").asInt, o.get("ascent").asInt)
        }
    }
}
