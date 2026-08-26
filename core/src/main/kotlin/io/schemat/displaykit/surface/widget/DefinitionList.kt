package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode

data class DefinitionEntry(
    val key: String,
    val label: String,
    val value: String,
    val labelColor: DkColor? = null,
    val valueColor: DkColor? = null
)

data class DefinitionListStyle(
    val width: Int,
    val labelWidth: Int = width / 2,
    val rowHeight: Int = TextMetrics.FONT_LINE_HEIGHT_PX,
    val rowGap: Int = 1,
    val columnGap: Int = 6
) {
    init {
        require(width > 0) { "Definition list width must be positive." }
        require(labelWidth > 0 && labelWidth < width) { "labelWidth must lie inside the list width." }
        require(rowHeight >= TextMetrics.FONT_LINE_HEIGHT_PX) {
            "Definition rows must fit one text line (${TextMetrics.FONT_LINE_HEIGHT_PX}px)."
        }
        require(rowGap >= 0 && columnGap >= 0) { "Definition list gaps cannot be negative." }
        require(labelWidth + columnGap < width) { "Definition columns leave no room for a value." }
    }
}

/** Stable-key two-column label/value list. */
class DefinitionList(
    id: String,
    initialEntries: List<DefinitionEntry> = emptyList(),
    val style: DefinitionListStyle
) : AutoCloseable {
    private class Holder(var entry: DefinitionEntry)

    private val rows = KeyedColumn(
        id = id,
        initialItems = initialEntries,
        key = DefinitionEntry::key,
        gap = style.rowGap
    ) { rowId, entry ->
        val holder = Holder(entry)
        KeyedRow(
            WidgetNode(rowId, PxSize(style.width, style.rowHeight)) { painter, rect ->
                val current = holder.entry
                val y = TextMetrics.rowAlignedY(
                    rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2
                )
                painter.label(
                    TextMetrics.ellipsize(current.label, style.labelWidth),
                    rect.x,
                    y,
                    current.labelColor
                )
                val valueWidth = style.width - style.labelWidth - style.columnGap
                painter.label(
                    TextMetrics.ellipsize(current.value, valueWidth),
                    rect.x + style.labelWidth + style.columnGap,
                    y,
                    current.valueColor
                )
            },
            update = { holder.entry = it }
        )
    }

    val node get() = rows.node
    fun keys(): List<String> = rows.keys()
    fun reconcile(entries: List<DefinitionEntry>): KeyedReconcileResult = rows.reconcile(entries)
    override fun close() = rows.close()
}
