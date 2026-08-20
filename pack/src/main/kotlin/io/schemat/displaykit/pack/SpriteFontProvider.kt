package io.schemat.displaykit.pack

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.schemat.displaykit.sprite.SpriteGlyphs

/**
 * Emits bitmap font providers that reference vanilla sprite textures directly.
 *
 * A provider's `file` resolves to `assets/<ns>/textures/<path>`, so pointing it
 * at `minecraft:gui/sprites/hud/hotbar.png` reuses the texture the client
 * already has. The pack therefore carries only JSON — no image bytes at all.
 *
 * A 1x1 `chars` grid makes the whole texture a single glyph, and its advance
 * scales with `height`, which is how these render at true aspect ratio where
 * an `AtlasSprite` component would be locked square.
 *
 * Unlike `AtlasSprite`, bitmap glyphs multiply by the style colour, so they can
 * be tinted — cleanly on greyscale sources, muddily on coloured ones.
 */
object SpriteFontProvider : AssetProvider {

    override fun contributeAssets(builder: PackBuilder) {
        val variants = SpriteGlyphs.requested()
        if (variants.isEmpty()) return

        val providers = JsonArray()
        for (variant in variants) {
            val entry = variant.entry

            // ascent <= height is enforced by the client
            // ("Ascent {} higher than height {}"). Negative is unbounded, so
            // downward shifts are free and upward shifts are capped.
            val ascent = (entry.height + variant.yOffset).coerceAtMost(entry.height)

            providers.add(JsonObject().apply {
                addProperty("type", "bitmap")
                addProperty("file", entry.texture)
                addProperty("height", entry.height)
                addProperty("ascent", ascent)
                add("chars", JsonArray().apply {
                    add(String(Character.toChars(variant.codepoint)))
                })
            })
        }

        val font = JsonObject().apply { add("providers", providers) }
        builder.addJson("assets/displaykit/font/sprites.json", font.toString())
    }
}
