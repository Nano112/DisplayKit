package io.schemat.displaykit.pack

import com.google.gson.JsonParser
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
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

    /** Resolve every crop of [id] at [yOffset], as a paint pass would. */
    private fun resolveAll(id: SpriteId, yOffset: Int): List<Int?> =
        SliceCatalog.regionsFor(id)!!.map { SpriteSliceProvider.codepointFor(id, it.x, it.y, yOffset) }

    private fun providersOf(b: PackBuilder) = JsonParser.parseString(
        b.capturedJson("assets/displaykit/font/sprite_slices.json")
    ).asJsonObject.getAsJsonArray("providers")

    @Test
    fun requestingASpriteEmitsItsCropsAndAFontProvider() {
        SpriteSliceProvider.request(button)
        resolveAll(button, 0)
        val b = PackBuilder(PackConfig())
        SpriteSliceProvider.contributeAssets(b)
        assertEquals(9, b.imageCount())
        assertEquals(9, providersOf(b).size())
    }

    @Test
    fun sliceCodepointsComeFromTheSliceRange() {
        SpriteSliceProvider.request(button)
        for (cp in resolveAll(button, 0)) {
            assertNotNull(cp)
            assertTrue(cp >= SpriteGlyphs.SLICE_BASE_CODEPOINT,
                "slice codepoints must not collide with whole-sprite glyphs")
        }
    }

    @Test
    fun resolvingTwiceReusesTheSameCodepoints() {
        SpriteSliceProvider.request(button)
        val first = resolveAll(button, 0)
        val second = resolveAll(button, 0)
        assertEquals(first, second, "a codepoint handed out must never move under a client")
    }

    // Vertical placement is baked into a font provider's `ascent`, which is
    // fixed per entry. So a crop drawn at N distinct within-row offsets costs N
    // entries — the same rule SpriteGlyphs applies to whole sprites. Emitting
    // one entry per CROP, with ascent = height, put every frame at a
    // non-multiple-of-10 y on its row's baseline instead of where it was asked
    // for.

    @Test
    fun eachProviderAscentIsItsCropHeightPlusItsOffset() {
        SpriteSliceProvider.request(button)
        resolveAll(button, 0)
        resolveAll(button, -7)

        val b = PackBuilder(PackConfig())
        SpriteSliceProvider.contributeAssets(b)

        // Index the emitted entries by codepoint so each can be checked against
        // the variant it was emitted for.
        val byCodepoint = providersOf(b).associate { p ->
            val o = p.asJsonObject
            o.getAsJsonArray("chars")[0].asString.codePointAt(0) to o
        }
        assertEquals(18, byCodepoint.size, "two offsets over nine crops is eighteen entries")

        for (v in SpriteSliceProvider.variants()) {
            val o = byCodepoint.getValue(v.codepoint)
            assertEquals(v.crop.h, o.get("height").asInt)
            assertEquals(
                v.crop.h + v.yOffset, o.get("ascent").asInt,
                "ascent must be crop height + yOffset for variant $v"
            )
        }
    }

    @Test
    fun aCropAtTwoOffsetsGetsTwoCodepointsAndTwoProviderEntries() {
        SpriteSliceProvider.request(button)
        val flat = SpriteSliceProvider.codepointFor(button, 0, 0, 0)
        val shifted = SpriteSliceProvider.codepointFor(button, 0, 0, -4)
        assertNotNull(flat); assertNotNull(shifted)
        assertNotEquals(flat, shifted, "one codepoint cannot carry two ascents")

        val b = PackBuilder(PackConfig())
        SpriteSliceProvider.contributeAssets(b)
        assertEquals(2, providersOf(b).size(), "one entry per variant, not per crop")
        assertEquals(9, b.imageCount(), "variants re-point at crops; images do not multiply")
    }

    @Test
    fun codepointsAreAllocatedOnDemandNotAtCatalogLoad() {
        // The catalogue is loaded lazily and shared; merely reading it must not
        // burn codepoints for crops nobody draws.
        SliceCatalog.all()
        assertEquals(0, SpriteSliceProvider.variantCount())
        SpriteSliceProvider.request(button)
        assertEquals(0, SpriteSliceProvider.variantCount(), "request alone allocates nothing")
        SpriteSliceProvider.codepointFor(button, 0, 0, 0)
        assertEquals(1, SpriteSliceProvider.variantCount())
    }

    @Test
    fun anUnknownCropResolvesToNullWithoutAllocating() {
        SpriteSliceProvider.request(button)
        assertNull(SpriteSliceProvider.codepointFor(button, 9999, 9999, 0))
        assertEquals(0, SpriteSliceProvider.variantCount())
    }

    @Test
    fun aPositiveOffsetIsRejected() {
        SpriteSliceProvider.request(button)
        val e = assertFailsWith<IllegalArgumentException> {
            SpriteSliceProvider.codepointFor(button, 0, 0, 3)
        }
        assertTrue(e.message!!.contains("ascent"), "the message must explain the ascent limit")
    }
}
