package io.schemat.displaykit.sprite

import io.schemat.displaykit.render.TextMetrics
import java.util.logging.Level
import java.util.logging.Logger

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
            if (entry.id.toString() == traceId) {
                // Deliberately an exception used as a stack sample, not thrown:
                // "which paint minted this variant" is the one question a leak
                // warning cannot answer, and it is exactly the question that
                // matters -- a sprite drawn at four ascents is being drawn from
                // somewhere other than where it was warmed, and only the call
                // stack says where.
                Logger.getLogger("displaykit/sprite").log(
                    Level.WARNING,
                    "GLYPH TRACE ${entry.id} new variant ascent=$ascent h=$renderHeight",
                    Throwable("glyph variant allocation site")
                )
            }
            GlyphVariant(entry, ascent, next++, renderHeight)
        }.codepoint
    }

    /**
     * Sprite id to log an allocation stack for, or null. Set via `/dk glyphtrace`.
     *
     * A sprite that mints more variants than it was warmed with is being drawn
     * from a second place, and no counter says which. This does.
     */
    @Volatile
    var traceId: String? = null

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

    /**
     * Allocate every variant [entry] can need when drawn at an ARBITRARY y.
     *
     * A glyph's ascent is baked per variant and depends only on the target y
     * modulo the line pitch, so a sprite drawn at unconstrained y needs a
     * small, FIXED set of variants -- not one per pixel. Warming that set up
     * front turns "this panel happens to sit two pixels lower today" from a
     * pack rebuild (and a re-download for every connected client) into a
     * lookup.
     *
     * This is what [io.schemat.displaykit.surface.SurfacePainter.fill] needs:
     * a fill tiles its sprite wherever its rect falls, and no caller can
     * reasonably be asked to predict those y values. Clicking a tab in the
     * sprite picker rebuilt the pack for exactly this reason -- the new tab
     * strip put a fill one phase off the phases already warmed.
     *
     * Two spans, because [GlyphPlacement.resolve] clamps at row 0: a short
     * glyph near the top of the canvas resolves to ascents the general case
     * never produces, and warming only the general case would miss them.
     */
    @JvmOverloads
    fun warmAllPhases(entry: SpriteEntry, renderHeight: Int = entry.height) {
        val pitch = TextMetrics.FONT_LINE_HEIGHT_PX
        val spans = listOf(0 until pitch, CLAMP_FREE_Y until CLAMP_FREE_Y + pitch)
        for (span in spans) {
            for (y in span) {
                GlyphPlacement.resolve(y, renderHeight)?.let {
                    request(entry, it.ascent, renderHeight)
                }
            }
        }
    }

    /**
     * A y far enough down the canvas that [GlyphPlacement.resolve] never
     * clamps to row 0, so the phases warmed from here are the general ones.
     */
    private const val CLAMP_FREE_Y = 100

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
