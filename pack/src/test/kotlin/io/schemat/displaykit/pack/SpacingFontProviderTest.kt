package io.schemat.displaykit.pack

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpacingFontProviderTest {

    private fun fontJson(): String {
        val builder = PackBuilder(PackConfig())
        SpacingFontProvider.contributeAssets(builder)
        return builder.capturedJson("assets/displaykit/font/spacing.json")
    }

    @Test
    fun writesExactlyOneSpaceProvider() {
        val providers = JsonParser.parseString(fontJson())
            .asJsonObject.getAsJsonArray("providers")
        assertEquals(1, providers.size())
        assertEquals("space", providers[0].asJsonObject.get("type").asString)
    }

    @Test
    fun declaresAllSixteenAdvancesWithTheExactCodepointToValueMapping() {
        val provider = JsonParser.parseString(fontJson())
            .asJsonObject.getAsJsonArray("providers")[0].asJsonObject
        val advances = provider.getAsJsonObject("advances")

        assertEquals(16, advances.size(), "expected all 16 advances, got keys: ${advances.keySet()}")

        for ((codepoint, expected) in SpacingFontProvider.ADVANCES) {
            val key = String(Character.toChars(codepoint))
            assertTrue(advances.has(key), "missing advance for codepoint 0x${codepoint.toString(16)}")
            assertEquals(
                expected, advances.get(key).asInt,
                "wrong advance for codepoint 0x${codepoint.toString(16)}"
            )
        }
    }

    @Test
    fun codepointsMatchTheNegativeAndPositiveRangesUsedBySpacing() {
        // io.schemat.displaykit.sprite.Spacing: NEGATIVE at U+F001-U+F080,
        // POSITIVE at U+F101-U+F180.
        for (codepoint in SpacingFontProvider.ADVANCES.keys) {
            val inNegativeRange = codepoint in 0xF001..0xF080
            val inPositiveRange = codepoint in 0xF101..0xF180
            assertTrue(
                inNegativeRange || inPositiveRange,
                "codepoint 0x${codepoint.toString(16)} is outside the expected spacing ranges"
            )
        }
    }

    @Test
    fun writesNoImagesIntoThePack() {
        val builder = PackBuilder(PackConfig())
        SpacingFontProvider.contributeAssets(builder)
        assertEquals(0, builder.imageCount(), "the space provider must ship zero textures")
    }
}
