package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.hud.ProgressBarColor
import io.schemat.displaykit.hud.ProgressBarModel
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode

data class ProgressBarViewStyle(
    val width: Int = 240,
    val height: Int = 14,
    val trackColor: DkColor = DkColor(220, 24, 26, 31),
    val textColor: DkColor = DkColor.WHITE,
    val showText: Boolean = true,
) {
    init {
        require(width > 0) { "Progress bar width must be positive" }
        require(height > 0) { "Progress bar height must be positive" }
        if (showText) require(height >= TextMetrics.FONT_LINE_HEIGHT_PX) {
            "A text-bearing progress bar must fit one font line"
        }
    }
}

/** Surface renderer for the same [ProgressBarModel] used by native boss bars. */
class ProgressBarView(
    id: String,
    private val model: () -> ProgressBarModel,
    val style: ProgressBarViewStyle = ProgressBarViewStyle(),
) {
    val node = WidgetNode(id, PxSize(style.width, style.height)) { painter, rect ->
        val current = model()
        if (!current.visible) return@WidgetNode
        painter.fill(style.trackColor, rect)
        // SurfacePainter owns the renderer-specific strategy for thin fills,
        // so progress remains exact instead of snapping to glyph-sized steps.
        val filled = (rect.w * current.progress).toInt().coerceIn(0, rect.w)
        if (filled > 0) painter.fill(current.color.surfaceColor(), Rect(rect.x, rect.y, filled, rect.h))
        if (style.showText) {
            val text = TextMetrics.ellipsize(current.title.plain(), rect.w - 6)
            painter.faceLabel(
                text,
                rect.x + (rect.w - TextMetrics.textWidthPx(text)) / 2,
                TextMetrics.rowAlignedY(rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2),
                style.textColor,
            )
        }
    }

    private fun ProgressBarColor.surfaceColor(): DkColor = when (this) {
        ProgressBarColor.PINK -> DkColor.fromRGB(255, 85, 255)
        ProgressBarColor.BLUE -> DkColor.fromRGB(85, 85, 255)
        ProgressBarColor.RED -> DkColor.fromRGB(255, 85, 85)
        ProgressBarColor.GREEN -> DkColor.fromRGB(85, 255, 85)
        ProgressBarColor.YELLOW -> DkColor.fromRGB(255, 255, 85)
        ProgressBarColor.PURPLE -> DkColor.fromRGB(170, 0, 170)
        ProgressBarColor.WHITE -> DkColor.WHITE
    }
}
