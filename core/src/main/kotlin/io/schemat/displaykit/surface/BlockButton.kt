package io.schemat.displaykit.surface

import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex

/**
 * A vanilla button face intended to sit over a real block-display slab.
 *
 * `gui/widget/button` is purpose-made button art rather than a tab borrowed
 * from another screen. Its native 200x20 size is also its useful minimum:
 * DisplayKit's nine-slice crops are fixed glyphs, so shrinking its 194px
 * centre would require a crop that does not exist. Callers should size with
 * [widthFor] and [HEIGHT], then let [blockButton] provide the volume, face,
 * label and hit target as one control.
 */
object BlockButton {
    const val MIN_WIDTH = 200
    const val HEIGHT = 20

    enum class State { NORMAL, HOVERED, SELECTED, SELECTED_HOVERED }

    private val NORMAL = SpriteId("gui", "widget/button")
    private val HIGHLIGHTED = SpriteId("gui", "widget/button_highlighted")
    private val PRESSED = SpriteId("gui", "widget/button_disabled")
    private val SELECTED_TINT = DkColor(255, 255, 220, 96)

    private fun spriteId(state: State): SpriteId = when (state) {
        State.NORMAL -> NORMAL
        State.HOVERED -> HIGHLIGHTED
        State.SELECTED -> PRESSED
        State.SELECTED_HOVERED -> HIGHLIGHTED
    }

    internal fun tint(state: State): DkColor? = when (state) {
        State.SELECTED, State.SELECTED_HOVERED -> SELECTED_TINT
        else -> null
    }

    internal fun entry(state: State): SpriteEntry? = SpriteIndex.bundled.get(spriteId(state))

    /** Every possible face, so hover never grows the pack mid-interaction. */
    fun statesFor(): List<SpriteEntry> =
        State.entries.mapNotNull(::entry).distinctBy { it.id }

    /** Smallest exact nine-slice width at least [minWidth]. */
    fun widthFor(minWidth: Int): Int {
        val sprite = entry(State.NORMAL) ?: return maxOf(minWidth, MIN_WIDTH)
        return NineSliceLayout.exactSizeFor(sprite, minWidth, HEIGHT).first
    }
}

/**
 * A physical button made from a local block-display extrusion with a sprite
 * face on its front. The solid starts at the panel and grows toward the
 * viewer without pushing unrelated surface layers forward.
 */
fun SurfacePainter.blockButton(
    id: String,
    rect: Rect,
    text: String,
    state: BlockButton.State = BlockButton.State.NORMAL,
    base: BlockStateRef? = null,
    baseThickness: Float = 0.0625f,
    onClick: () -> Unit = {}
) {
    val sprite = BlockButton.entry(state) ?: return
    if (rect.w < sprite.width || rect.h < sprite.height) return

    // The solid starts on the panel and grows toward the viewer. Its artwork
    // is translated directly to that local front face; no global depth layer
    // is advanced and nothing is allowed to punch through the panel.
    val faceDepth = if (base != null) baseThickness + FACE_BIAS else 0f
    if (base != null) blockExtrusion(base, rect, baseThickness)
    frame(sprite, rect, BlockButton.tint(state), depthOffset = faceDepth)

    val fittedText = TextMetrics.ellipsize(text, (rect.w - 8).coerceAtLeast(0))
    val textWidth = TextMetrics.textWidthPx(fittedText)
    val labelY = TextMetrics.rowAlignedY(
        rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2
    )
    faceLabel(
        fittedText,
        rect.x + (rect.w - textWidth) / 2,
        labelY,
        // A stretched nine-slice uses a microscopic fill coating in front of
        // its native-size corner sprites. Keep the label one local overlay
        // step beyond that coating without consuming a global surface layer.
        depthOffset = faceDepth + Surface.ENTITY_OVERLAY_Z_BIAS * 2f
    )
    region(id, rect, onClick)
}

private const val FACE_BIAS = 0.0001f
