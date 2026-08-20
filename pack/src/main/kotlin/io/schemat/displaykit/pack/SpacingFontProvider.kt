package io.schemat.displaykit.pack

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Emits the `space` font provider that backs `io.schemat.displaykit.sprite.Spacing`.
 *
 * `SpriteCanvas.toTextComponent()` closes the gap between composited items
 * with characters from two fonts: `displaykit:sprites` (written by
 * [SpriteFontProvider]) and `displaykit:spacing` (written here). Without this
 * provider registered, every compositor call site emits advance-only
 * characters with nothing behind them — missing-glyph boxes between every
 * sprite.
 *
 * This intentionally does NOT reuse `SpriteAssetProvider.registerSpacingIcons()`
 * as a call site: `SpriteAssetProvider.contributeAssets` also overwrites
 * `assets/minecraft/font/default.json` with a reference to its icon font,
 * which is invasive for every consumer of the pack, not just the compositor.
 * This provider writes only `assets/displaykit/font/spacing.json`.
 *
 * [ADVANCES] must match the `NEGATIVE`/`POSITIVE` tables in
 * `io.schemat.displaykit.sprite.Spacing` exactly — negative advances at
 * U+F001-U+F080, positive advances at U+F101-U+F180.
 */
object SpacingFontProvider : AssetProvider {

    /** Codepoint -> pixel advance. Mirrors io.schemat.displaykit.sprite.Spacing verbatim. */
    val ADVANCES: Map<Int, Int> = linkedMapOf(
        0xF001 to -1, 0xF002 to -2, 0xF004 to -4, 0xF008 to -8,
        0xF010 to -16, 0xF020 to -32, 0xF040 to -64, 0xF080 to -128,
        0xF101 to 1, 0xF102 to 2, 0xF104 to 4, 0xF108 to 8,
        0xF110 to 16, 0xF120 to 32, 0xF140 to 64, 0xF180 to 128
    )

    override fun contributeAssets(builder: PackBuilder) {
        builder.addJson("assets/displaykit/font/spacing.json", spacingJson())
    }

    /** The `space` provider JSON, built here so tests can assert on it directly. */
    fun spacingJson(): String {
        val advances = JsonObject().apply {
            for ((codepoint, advance) in ADVANCES) {
                addProperty(String(Character.toChars(codepoint)), advance)
            }
        }
        val provider = JsonObject().apply {
            addProperty("type", "space")
            add("advances", advances)
        }
        val font = JsonObject().apply {
            add("providers", JsonArray().apply { add(provider) })
        }
        return font.toString()
    }
}
