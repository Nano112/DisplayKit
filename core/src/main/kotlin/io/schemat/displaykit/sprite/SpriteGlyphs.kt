package io.schemat.displaykit.sprite

/**
 * Codepoint allocation for by-reference sprite glyphs.
 *
 * A bitmap font provider's `file` field resolves to
 * `assets/<ns>/textures/<path>`, so it can point at a *vanilla* texture that
 * every client already has. That gives true-aspect, tintable sprite glyphs
 * while shipping zero image bytes — the pack carries only JSON.
 *
 * Vertical placement rides on the provider's `ascent`, which is baked per
 * entry, so a sprite drawn at N distinct Y offsets costs N entries. Glyph
 * *count* is the budget here, not bytes.
 *
 * Codepoints come from Supplementary PUA-A (U+F0000-U+FFFFD). The Basic PUA is
 * already taken: SpriteAssetProvider allocates icons from U+E000 upward and
 * spacing.json claims U+F001-U+F200.
 */
object SpriteGlyphs {

    const val FONT_ID = "displaykit:sprites"

    /** Font id for cropped nine-slice regions, written by SpriteSliceProvider. */
    const val SLICE_FONT_ID = "displaykit:sprite_slices"

    /** First codepoint in Supplementary PUA-A. */
    const val BASE_CODEPOINT = 0xF0000

    /**
     * Slices live in their own range so [SpriteFontProvider] never mistakes a
     * cropped region for a whole-sprite glyph and emits a provider pointing at
     * the full texture.
     */
    const val SLICE_BASE_CODEPOINT = 0xF8000

    /** Last usable codepoint in Supplementary PUA-A. */
    const val MAX_CODEPOINT = 0xFFFFD

    data class GlyphVariant(
        val entry: SpriteEntry,
        val ascent: Int,
        val codepoint: Int,
        /**
         * Rendered height in text pixels — the provider's `height` field.
         *
         * The client scales a bitmap glyph by `height / sourcePixelHeight` and
         * scales its width by the same factor, so this is the one knob that
         * makes an oversized sprite fit a small cell. Defaults to the sprite's
         * native height, which renders 1:1.
         */
        val renderHeight: Int
    )

    private val variants = LinkedHashMap<Key, GlyphVariant>()
    private var next = BASE_CODEPOINT

    private data class Key(val id: SpriteId, val ascent: Int, val renderHeight: Int)

    /**
     * Codepoint for [entry] with a font-provider `ascent` of [ascent],
     * allocating one if this variant is new.
     *
     * `ascent` alone determines where the glyph's top sits, relative to the
     * origin of the line it is emitted on (`TextMetrics.GLYPH_TOP_BEARING_PX
     * - ascent` — see [io.schemat.displaykit.render.TextMetrics] and
     * [GlyphPlacement], which derives it from a target canvas Y), so a
     * sprite drawn at N distinct ascents costs N glyph entries.
     *
     * Defaults to [SpriteEntry.height] — the maximum legal ascent, and the
     * natural, non-canvas-clipped rendering used when a glyph is placed
     * directly (e.g. inline in chat text) rather than through
     * [SpriteCanvas].
     *
     * @throws IllegalArgumentException if [entry] is animated — a glyph would
     *   render the entire vertical strip rather than one frame.
     * @throws IllegalArgumentException if [ascent] exceeds [SpriteEntry.height]
     *   — the client throws `"Ascent {} higher than height {}"` and refuses
     *   to load the WHOLE font file if this is ever violated. Negative
     *   ascent is unbounded and always fine.
     */
    @JvmOverloads
    fun codepointFor(
        entry: SpriteEntry,
        ascent: Int = entry.height,
        renderHeight: Int = entry.height
    ): Int {
        require(!entry.animated) {
            "Sprite ${entry.id} is animated and cannot be a font glyph — " +
                "a glyph renders the whole strip. Use SpriteDisplay instead."
        }
        require(renderHeight > 0) {
            "Sprite ${entry.id} requested renderHeight $renderHeight; a glyph " +
                "must have a positive height."
        }
        // Against renderHeight, NOT the sprite's native height: the client
        // compares ascent to the `height` field it is given, so a scaled-down
        // glyph has a correspondingly smaller legal ascent.
        require(ascent <= renderHeight) {
            "Sprite ${entry.id} requested ascent $ascent, which exceeds its " +
                "render height $renderHeight — the client rejects the WHOLE font file " +
                "(\"Ascent {} higher than height {}\") if this is ever violated."
        }
        return variants.getOrPut(Key(entry.id, ascent, renderHeight)) {
            check(next < SLICE_BASE_CODEPOINT) {
                "Exhausted whole-sprite glyph space: allocating at codepoint " +
                    "0x${next.toString(16).uppercase()} would collide with slice " +
                    "codepoints, which start at 0x${SLICE_BASE_CODEPOINT.toString(16).uppercase()} " +
                    "(SLICE_BASE_CODEPOINT). At most ${SLICE_BASE_CODEPOINT - BASE_CODEPOINT} " +
                    "whole-sprite glyph variants are supported."
            }
            GlyphVariant(entry, ascent, next++, renderHeight)
        }.codepoint
    }

    /** The codepoint as a string — a surrogate pair, since these are > U+FFFF. */
    @JvmOverloads
    fun charsFor(
        entry: SpriteEntry,
        ascent: Int = entry.height,
        renderHeight: Int = entry.height
    ): String = String(Character.toChars(codepointFor(entry, ascent, renderHeight)))

    /** Allocate without needing the result, e.g. when pre-warming a pack. */
    @JvmOverloads
    fun request(
        entry: SpriteEntry,
        ascent: Int = entry.height,
        renderHeight: Int = entry.height
    ) {
        codepointFor(entry, ascent, renderHeight)
    }

    /** Every whole-sprite variant allocated so far, in allocation order. */
    fun requested(): List<GlyphVariant> = variants.values.toList()

    private var nextSlice = SLICE_BASE_CODEPOINT

    /**
     * Reserve a codepoint for a cropped slice.
     *
     * Slices are not whole-sprite glyphs — they reference a generated texture
     * rather than a vanilla one — so they are allocated separately and never
     * appear in [requested].
     */
    fun allocateSlice(): Int {
        check(nextSlice <= MAX_CODEPOINT) { "Exhausted slice glyph space" }
        return nextSlice++
    }

    fun clear() {
        variants.clear()
        next = BASE_CODEPOINT
        nextSlice = SLICE_BASE_CODEPOINT
    }
}
