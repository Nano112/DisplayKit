package io.schemat.displaykit.showcase

import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.pack.SpacingFontProvider
import io.schemat.displaykit.pack.SpriteFontProvider
import io.schemat.displaykit.pack.SpriteSliceProvider
import io.schemat.displaykit.sprite.SpriteGlyphs

/**
 * Register the glyph asset providers, run [paint], and resend the pack if
 * painting allocated new glyph variants.
 *
 * Every surface that composites sprites needs this exact sequence, and it is
 * silent when skipped: the client keeps whatever pack it already has, so
 * every glyph the new window needs renders as a missing-glyph box.
 * TerminalWindow shipped without the rebuild and was entirely illegible.
 *
 * Growth is checked on BOTH allocators a paint can grow — [SpriteGlyphs]'s
 * whole-sprite codepoints and [SpriteSliceProvider]'s cropped nine-slice
 * variants. Chrome (frames, tabs, scrollbars) allocates slices, not whole
 * glyphs, so checking only one half would silently skip the resend a
 * slice-only repaint needs — this is [PickerWindow]'s `||`, preserved here
 * rather than narrowed to just [SpriteGlyphs].
 *
 * `registerAssetProvider` is idempotent (a provider already registered is a
 * no-op), so calling this on every content-changing repaint — not just the
 * first — is safe and cheap.
 *
 * Rebuilding unconditionally would be wrong the other way: it would make
 * EVERY connected client re-download the pack on EVERY repaint. So this only
 * rebuilds when [paint] actually allocated something new.
 *
 * Returns true if a rebuild was triggered, so the caller can decide whether
 * to wait via [FabricPackIntegration.whenPackApplied].
 */
fun withPackSync(paint: () -> Unit): Boolean {
    FabricPackIntegration.registerAssetProvider(SpriteFontProvider)
    FabricPackIntegration.registerAssetProvider(SpacingFontProvider)
    FabricPackIntegration.registerAssetProvider(SpriteSliceProvider)

    val glyphsBefore = SpriteGlyphs.requested().size
    val slicesBefore = SpriteSliceProvider.variantCount()

    paint()

    val grew = SpriteGlyphs.requested().size > glyphsBefore ||
        SpriteSliceProvider.variantCount() > slicesBefore
    if (grew) FabricPackIntegration.rebuildAndResendToAll()
    return grew
}
