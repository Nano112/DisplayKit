package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.sprite.GlyphPlacement
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteFit
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.layout.CrossAxis
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.PxConstraints
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.ScrollNode
import io.schemat.displaykit.surface.layout.WidgetNode

/** Sizing and appearance for a uniform sprite palette. */
data class SpriteGridStyle(
    val cellSize: Int = 18,
    val pitch: Int = 20,
    val contentInset: Int = 1,
    val hoverBackground: DkColor = DkColor(48, 255, 255, 240)
) {
    init {
        require(cellSize > 0 && pitch >= cellSize)
        require(contentInset >= 0 && contentInset * 2 < cellSize)
    }
}

/**
 * A responsive, scrollable sprite grid.
 *
 * Column count comes from the width layout actually grants this node. Rows,
 * scroll pitch, cell hit regions, sprite fitting, and glyph preparation are
 * consequently one primitive rather than parallel arithmetic in its caller.
 */
class SpriteGridNode(
    id: String,
    private val items: List<SpriteEntry>,
    preloadItems: List<SpriteEntry> = items,
    possibleItemCounts: Collection<Int> = listOf(items.size),
    private val isHovered: (String) -> Boolean,
    private val onClick: (SpriteEntry) -> Unit,
    val style: SpriteGridStyle = SpriteGridStyle()
) : ScrollNode(id) {

    private val preload = preloadItems.filter { it.glyphEligible }.distinctBy { it.id }
    private val counts = (possibleItemCounts + items.size).distinct()
    private var columns = 1
    private var builtColumns = 0
    private var firstCell: WidgetNode? = null

    init {
        stepPx = style.pitch
        flexGrow = 1
        onPrepare = ::prepareGlyphs
    }

    override fun measureSelf(c: PxConstraints): PxSize {
        val innerWidth = c.deflate(padding).maxW
        columns = maxOf(1, (innerWidth - style.cellSize) / style.pitch + 1)
        if (columns != builtColumns) rebuildRows()
        return super.measureSelf(c)
    }

    private fun rebuildRows() {
        clearChildren()
        firstCell = null
        builtColumns = columns
        for (rowStart in items.indices.step(columns)) {
            val row = FlexNode(
                "$id-row-$rowStart",
                FlexDirection.ROW,
                gap = style.pitch - style.cellSize,
                crossAxis = CrossAxis.CENTER
            )
            row.height = style.pitch
            for (index in rowStart until minOf(rowStart + columns, items.size)) {
                val entry = items[index]
                val cellId = "$id-cell-$index"
                val cell = WidgetNode(cellId, PxSize(style.cellSize, style.cellSize)) { painter, rect ->
                    painter.slot(rect.x, rect.y)
                    if (isHovered(cellId)) {
                        val inset = style.contentInset
                        painter.fill(
                            style.hoverBackground,
                            Rect(
                                rect.x + inset,
                                rect.y + inset,
                                rect.w - inset * 2,
                                rect.h - inset * 2
                            )
                        )
                    }
                    val inset = style.contentInset
                    painter.iconFitted(
                        entry,
                        rect.x + inset,
                        rect.y + inset,
                        rect.w - inset * 2,
                        rect.h - inset * 2
                    )
                }
                cell.onEvent = { event ->
                    when (event) {
                        is SurfaceEvent.Click -> {
                            onClick(entry)
                            EventResult.CONSUMED
                        }
                        is SurfaceEvent.PointerEnter, is SurfaceEvent.PointerExit ->
                            EventResult.CONSUMED
                        else -> EventResult.PASS
                    }
                }
                if (firstCell == null) firstCell = cell
                row.addChild(cell)
            }
            addChild(row)
        }
    }

    /** Legal scroll extents for every content population this grid may show. */
    fun possibleMaxScrolls(): List<Int> {
        val step = style.pitch
        val viewport = rect().h - (rect().h % step)
        return counts.map { count ->
            val rows = (count + columns - 1) / columns
            (rows * step - viewport).coerceAtLeast(0)
        }.distinct()
    }

    private fun prepareGlyphs() {
        val cell = firstCell?.rect() ?: return
        val inset = style.contentInset
        val boxW = cell.w - inset * 2
        val boxH = cell.h - inset * 2
        for (entry in preload) {
            val height = SpriteFit.height(entry, boxW, boxH)
            val (_, y) = SpriteFit.origin(
                entry,
                cell.x + inset,
                // Prepare against the row's stable, unscrolled phase. The
                // retained tree is re-measured before every repaint, so using
                // its current viewport-shifted Y here would allocate another
                // glyph variant for each scroll position.
                cell.y + scrollPx + inset,
                boxW,
                boxH
            )
            val ascent = GlyphPlacement.resolve(y, height)?.ascent ?: continue
            SpriteGlyphs.request(entry, ascent, height)
        }
    }
}
