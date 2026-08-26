package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.PointerButton
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode

enum class DataCellAlignment { START, CENTER, END }

data class DataColumn<T>(
    val id: String,
    val label: String,
    val width: Int? = null,
    val weight: Int = 1,
    val alignment: DataCellAlignment = DataCellAlignment.START,
    val text: (T) -> String,
    val color: (T) -> DkColor? = { null }
) {
    init {
        require(id.isNotBlank()) { "A data column id cannot be blank." }
        require(width == null || width > 0) { "A fixed data column width must be positive." }
        require(weight > 0) { "A data column weight must be positive." }
    }
}

data class DataEntry<T>(val key: String, val value: T)

data class DataListStyle(
    val width: Int,
    val rowHeight: Int = TextMetrics.FONT_LINE_HEIGHT_PX,
    val rowGap: Int = 1,
    val columnGap: Int = 6,
    val headerColor: DkColor? = null,
    val hoverBackground: DkColor? = null,
    val selectedBackground: DkColor? = null
) {
    init {
        require(width > 0) { "Data list width must be positive." }
        require(rowHeight >= TextMetrics.FONT_LINE_HEIGHT_PX) { "Data rows must fit one text line." }
        require(rowGap >= 0 && columnGap >= 0) { "Data list gaps cannot be negative." }
    }
}

/** Retained keyed table with one shared column-width calculation. */
class DataList<T>(
    id: String,
    val columns: List<DataColumn<T>>,
    initialEntries: List<DataEntry<T>> = emptyList(),
    val style: DataListStyle,
    private val selectedKey: () -> String? = { null },
    private val isHovered: (String) -> Boolean = { false },
    private val onSelected: ((DataEntry<T>) -> Unit)? = null,
    private val onActivated: ((DataEntry<T>, PointerButton) -> Unit)? = null
) : AutoCloseable {
    private class Holder<T>(var entry: DataEntry<T>)

    val node = FlexNode(id, FlexDirection.COLUMN, gap = style.rowGap)
    private val widths = resolveWidths(columns, style)
    private val header = WidgetNode("$id-header", PxSize(style.width, style.rowHeight)) { painter, rect ->
        paintCells(
            rect.x,
            rect.y,
            rect.h,
            columns.map { it.label },
            columns.map { DataCellAlignment.START },
            columns.map { style.headerColor },
            painter
        )
    }
    private val rows = KeyedColumn(
        id = "$id-body",
        initialItems = initialEntries,
        key = DataEntry<T>::key,
        gap = style.rowGap
    ) { rowId, entry ->
        val holder = Holder(entry)
        KeyedRow(
            WidgetNode(rowId, PxSize(style.width, style.rowHeight)) { painter, rect ->
                val background = when {
                    selectedKey() == holder.entry.key -> style.selectedBackground
                    isHovered(rowId) -> style.hoverBackground
                    else -> null
                }
                if (background != null) painter.fill(background, rect)
                val value = holder.entry.value
                paintCells(
                    rect.x,
                    rect.y,
                    rect.h,
                    columns.map { it.text(value) },
                    columns.map { it.alignment },
                    columns.map { it.color(value) },
                    painter
                )
            }.also { row ->
                row.onEvent = { event ->
                    when {
                        event is SurfaceEvent.Click && (onSelected != null || onActivated != null) -> {
                            onSelected?.invoke(holder.entry)
                            onActivated?.invoke(holder.entry, event.button)
                            EventResult.CONSUMED
                        }
                        event is SurfaceEvent.PointerEnter || event is SurfaceEvent.PointerExit ->
                            if (onSelected != null || onActivated != null) EventResult.CONSUMED else EventResult.PASS
                        else -> EventResult.PASS
                    }
                }
            },
            update = { holder.entry = it }
        )
    }

    init {
        require(columns.isNotEmpty()) { "A data list needs at least one column." }
        val duplicates = columns.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
            "Data column ids must be unique (duplicates: ${duplicates.joinToString()})."
        }
        node.addChild(header)
        node.addChild(rows.node)
    }

    fun keys(): List<String> = rows.keys()
    fun reconcile(entries: List<DataEntry<T>>): KeyedReconcileResult = rows.reconcile(entries)
    override fun close() = rows.close()

    private fun paintCells(
        x: Int,
        y: Int,
        height: Int,
        values: List<String>,
        alignments: List<DataCellAlignment>,
        colors: List<DkColor?>,
        painter: io.schemat.displaykit.surface.SurfacePainter
    ) {
        var cursor = x
        val baseline = TextMetrics.rowAlignedY(y + (height - TextMetrics.FONT_LINE_HEIGHT_PX) / 2)
        values.indices.forEach { index ->
            val width = widths[index]
            val text = TextMetrics.ellipsize(values[index], width)
            val textWidth = TextMetrics.textWidthPx(text)
            val textX = when (alignments[index]) {
                DataCellAlignment.START -> cursor
                DataCellAlignment.CENTER -> cursor + (width - textWidth) / 2
                DataCellAlignment.END -> cursor + width - textWidth
            }
            painter.label(text, textX, baseline, colors[index])
            cursor += width + style.columnGap
        }
    }

    private companion object {
        fun <T> resolveWidths(columns: List<DataColumn<T>>, style: DataListStyle): List<Int> {
            require(columns.isNotEmpty()) { "A data list needs at least one column." }
            val gapTotal = style.columnGap * (columns.size - 1)
            val fixed = columns.sumOf { it.width ?: 0 }
            val flexible = columns.filter { it.width == null }
            val available = style.width - gapTotal - fixed
            require(available >= flexible.size) { "Data columns do not fit the configured width." }
            if (flexible.isEmpty()) {
                require(available == 0) { "Fixed data columns must exactly fill the configured width." }
                return columns.map { it.width!! }
            }
            val totalWeight = flexible.sumOf { it.weight }
            var remainder = available
            var remainingWeight = totalWeight
            return columns.map { column ->
                column.width ?: run {
                    val width = if (remainingWeight == column.weight) remainder
                    else available * column.weight / totalWeight
                    remainder -= width
                    remainingWeight -= column.weight
                    width
                }
            }
        }
    }
}
