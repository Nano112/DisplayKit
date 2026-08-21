package io.schemat.displaykit.surface

/**
 * How a [Surface] turns its painted content into entities.
 *
 * DisplayKit has two independent ways to put a sprite in front of a player,
 * and this is the switch between them:
 *
 * - [COMPOSITED] composites every painted element onto one shared canvas per
 *   depth layer, then emits each layer as a bitmap-font-backed text
 *   component ([Surface.toEntities]'s original behaviour, unchanged by this
 *   type). Full fidelity -- true nine-slice frames, exact glyph sizes,
 *   crops -- but every glyph is a DisplayKit-generated font entry, so it
 *   renders only for a viewer whose client has downloaded DisplayKit's
 *   resource pack. A window with ~9 depth layers costs ~9 entities.
 *
 * - [ENTITIES] paints nothing onto a canvas at all. Each painted element
 *   (one `icon`, one `label`, one `fill`, ...) becomes its OWN entity,
 *   carrying vanilla's native atlas-sprite text-component content
 *   (`Component.object(AtlasSprite)`, decompiled from the 1.21.11 client as
 *   a record of just `atlas` + `sprite`). That content type renders with
 *   **zero resource pack** -- it is built into every vanilla client -- but
 *   its glyph is a client-fixed 8x8 quad with no sub-region mechanism, so:
 *     - size is recovered by giving the glyph its own entity and scaling
 *       that entity's transformation, not by choosing a different glyph;
 *     - aspect ratio is recovered with a NON-UNIFORM scale (`targetW/8`,
 *       `targetH/8`) rather than DisplayKit's usual uniform `pixelScale`;
 *     - true nine-slice is impossible (no crop), so [SurfacePainter.frame]
 *       degrades -- see the KDoc on `Surface.recordFrame` for exactly how.
 *   Costs one entity per painted element (a picker grid page can be dozens),
 *   trading entity count for the ability to render with no pack at all.
 *
 * - [AUTO] resolves to [COMPOSITED] when a [SliceGlyphSource] is installed
 *   (the platform layer installs one only once the pack pipeline is live)
 *   and to [ENTITIES] otherwise. This is [Surface.renderMode]'s default: a
 *   surface painted before the pack pipeline exists -- or on a platform that
 *   never wires one up -- still renders something, instead of the
 *   `[DisplayKit surface unavailable: resource pack disabled]` fallback
 *   [Surface.toEntity] falls back to today.
 *
 * The picker's `/dk picker nopack` sets this to [ENTITIES] explicitly (not
 * [AUTO]) so the demo is provably pack-free regardless of whether some OTHER
 * surface on the same server has already installed a [SliceGlyphSource].
 */
enum class RenderMode {
    COMPOSITED,
    ENTITIES,
    AUTO;

    companion object {
        /**
         * [AUTO] resolves against whether a [SliceGlyphSource] is installed;
         * [COMPOSITED] and [ENTITIES] are already resolved and pass through
         * unchanged. [Surface] calls this once per paint/render pass rather
         * than callers checking [SliceGlyphSource.installed] by hand, so the
         * one rule ("what does AUTO mean") lives in exactly one place.
         */
        @JvmStatic
        fun resolve(mode: RenderMode): RenderMode = when (mode) {
            AUTO -> if (SliceGlyphSource.installed != null) COMPOSITED else ENTITIES
            COMPOSITED, ENTITIES -> mode
        }
    }
}
