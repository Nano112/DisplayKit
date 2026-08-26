package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.sprite.SpriteFit
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.layout.CanvasInitialPosition
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.VirtualCanvasNode
import io.schemat.displaykit.surface.layout.WidgetNode

/** One addressable cell in a uniform two-dimensional map. */
data class TileMapCell<T>(
    val id: String,
    val x: Int,
    val y: Int,
    val value: T
)

data class TileMapStyle(
    val tileSprite: SpriteId = SpriteId("blocks", "block/lightning_rod_on"),
    val mapBackground: SpriteId = CartographyMapView.MAP_BACKGROUND,
    val cellSize: Int = 8,
    val gap: Int = 1,
    val contentPadding: Int = 20,
    val parchment: DkColor = DkColor.fromRGB(70, 54, 39),
    val selected: DkColor = DkColor.fromRGB(252, 211, 77),
    val hovered: DkColor = DkColor.fromRGB(245, 224, 181),
    val panControls: CanvasPanControlsStyle = CanvasPanControlsStyle()
) {
    init {
        require(cellSize >= 3) { "Tile-map cells must be at least 3px." }
        require(gap >= 0) { "Tile-map gap cannot be negative." }
        require(contentPadding >= 0) { "Tile-map padding cannot be negative." }
    }
}

/**
 * A pannable, uniformly tiled data map over vanilla cartography parchment.
 *
 * Tile data is painted into shared composited layers, so a 25x25 logical map
 * remains a bounded set of display entities rather than 625 block displays.
 * Every tile still owns a stable hit node and application identity.
 */
class TileMapView<T>(
    id: String,
    val columns: Int,
    val rows: Int,
    cells: List<TileMapCell<T>>,
    private val color: (TileMapCell<T>) -> DkColor,
    private val selectedId: () -> String?,
    private val isHovered: (String) -> Boolean,
    private val onSelected: (TileMapCell<T>) -> Unit,
    onViewportChanged: () -> Unit,
    val style: TileMapStyle = TileMapStyle()
) {
    private val tile = SpriteIndex.bundled.get(style.tileSprite)
    private val background = SpriteIndex.bundled.get(style.mapBackground)
    private val pitch = style.cellSize + style.gap

    val contentSize = contentSize(columns, rows, style)
    val canvas = VirtualCanvasNode(
        id = id,
        contentSize = contentSize,
        initialPosition = CanvasInitialPosition.CENTER,
        viewportAspectRatio = 1f,
        onViewportChanged = onViewportChanged
    )
    val node: VirtualCanvasNode get() = canvas

    init {
        val duplicateIds = cells.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) {
            "Tile-map cell ids must be unique (duplicates: ${duplicateIds.joinToString()})."
        }
        cells.forEach { cell ->
            require(cell.id.isNotBlank()) { "Tile-map cell id cannot be blank." }
            require(cell.x in 0 until columns && cell.y in 0 until rows) {
                "Tile-map cell '${cell.id}' at (${cell.x}, ${cell.y}) is outside ${columns}x$rows."
            }
        }

        canvas.renderBackground = { painter, rect ->
            painter.fill(style.parchment, rect)
            background?.let { painter.iconFitted(it, rect.x, rect.y, rect.w, rect.h) }
        }

        cells.forEach { cell ->
            val nodeId = "$id-cell-${cell.id}"
            val cellNode = WidgetNode(nodeId, PxSize(style.cellSize, style.cellSize)) { painter, rect ->
                val emphasized = selectedId() == cell.id || isHovered(nodeId)
                if (emphasized) {
                    painter.fill(
                        if (selectedId() == cell.id) style.selected else style.hovered,
                        rect
                    )
                }
                val inset = if (emphasized) 1 else 0
                tile?.let {
                    painter.iconFitted(
                        it,
                        rect.x + inset,
                        rect.y + inset,
                        rect.w - inset * 2,
                        rect.h - inset * 2,
                        color(cell)
                    )
                }
            }
            cellNode.onEvent = { event ->
                when (event) {
                    is SurfaceEvent.Click -> {
                        onSelected(cell)
                        EventResult.CONSUMED
                    }
                    is SurfaceEvent.PointerEnter, is SurfaceEvent.PointerExit -> EventResult.CONSUMED
                    else -> EventResult.PASS
                }
            }
            canvas.addAt(
                cellNode,
                style.contentPadding + cell.x * pitch,
                style.contentPadding + cell.y * pitch
            )
        }

        canvas.addPanControls("$id-pan", isHovered, style.panControls)
        canvas.onPrepare = {
            warmTileGeometry()
            background?.let {
                val viewport = canvas.viewportRect()
                SpriteGlyphs.warmAllPhases(it, SpriteFit.height(it, viewport.w, viewport.h))
            }
        }
    }

    /**
     * Prepare the two geometries a tile can actually paint.
     *
     * The normal cell uses the full box; selection and hover inset it by one
     * pixel on every side. A glyph's rendered height is part of its cache key,
     * so warming only the sprite's native height (or only the normal cell)
     * makes the first hover allocate a new provider variant and resend the
     * resource pack. Prepare both fitted heights once per map rather than once
     * per cell -- a 25x25 map must not do 625 identical warm-up passes.
     */
    private fun warmTileGeometry() {
        val entry = tile ?: return
        SpriteGlyphs.warmAllPhases(
            entry,
            SpriteFit.height(entry, style.cellSize, style.cellSize)
        )
        val emphasizedSize = style.cellSize - 2
        SpriteGlyphs.warmAllPhases(
            entry,
            SpriteFit.height(entry, emphasizedSize, emphasizedSize)
        )
    }

    private companion object {
        fun contentSize(columns: Int, rows: Int, style: TileMapStyle): PxSize {
            require(columns > 0 && rows > 0) { "Tile-map dimensions must be positive." }
            val pitch = style.cellSize + style.gap
            return PxSize(
                style.contentPadding * 2 + columns * pitch - style.gap,
                style.contentPadding * 2 + rows * pitch - style.gap
            )
        }
    }
}
