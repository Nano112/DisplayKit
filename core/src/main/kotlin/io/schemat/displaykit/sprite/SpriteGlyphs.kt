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
        val yOffset: Int,
        val codepoint: Int
    )

    private val variants = LinkedHashMap<Key, GlyphVariant>()
    private var next = BASE_CODEPOINT

    private data class Key(val id: SpriteId, val yOffset: Int)

    /**
     * Codepoint for [entry] drawn at [yOffset] pixels below its natural
     * baseline, allocating one if this variant is new.
     *
     * @throws IllegalArgumentException if [entry] is animated — a glyph would
     *   render the entire vertical strip rather than one frame.
     * @throws IllegalArgumentException if [yOffset] is positive — upward
     *   shift is not representable. The client enforces `ascent <= height`,
     *   and `yOffset = 0` already puts `ascent` at its maximum (`height`), so
     *   there is no slack to shift into; a by-reference vanilla texture has
     *   no padding to exploit either.
     */
    fun codepointFor(entry: SpriteEntry, yOffset: Int = 0): Int {
        require(!entry.animated) {
            "Sprite ${entry.id} is animated and cannot be a font glyph — " +
                "a glyph renders the whole strip. Use SpriteDisplay instead."
        }
        require(yOffset <= 0) {
            "Sprite ${entry.id} requested yOffset $yOffset, but positive " +
                "offsets are not supported — ascent <= height is client-enforced " +
                "and ascent already sits at its maximum (height) when yOffset = 0. " +
                "Shift the whole composition down instead, or use SpriteDisplay " +
                "for free positioning."
        }
        return variants.getOrPut(Key(entry.id, yOffset)) {
            check(next <= MAX_CODEPOINT) { "Exhausted Supplementary PUA-A glyph space" }
            GlyphVariant(entry, yOffset, next++)
        }.codepoint
    }

    /** The codepoint as a string — a surrogate pair, since these are > U+FFFF. */
    fun charsFor(entry: SpriteEntry, yOffset: Int = 0): String =
        String(Character.toChars(codepointFor(entry, yOffset)))

    /** Allocate without needing the result, e.g. when pre-warming a pack. */
    fun request(entry: SpriteEntry, yOffset: Int = 0) {
        codepointFor(entry, yOffset)
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
