package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.property.PropertyField
import io.schemat.displaykit.property.PropertySheetModel
import io.schemat.displaykit.state.PaginationModel
import io.schemat.displaykit.state.StateScope
import io.schemat.displaykit.surface.layout.CrossAxis
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.SwitchNode

data class PagedPropertySheetStyle(
    val gap: Int = 4,
    val controlsVisibleForSinglePage: Boolean = false,
) {
    init { require(gap >= 0) { "Paged property-sheet gap cannot be negative" } }
}

/**
 * Bounded property editor that composes [PropertySheetView] pages and shared
 * sprite navigation. Large schemas cannot overflow their window, and feature
 * code never computes a field viewport or pager state.
 */
class PagedPropertySheetView(
    id: String,
    scope: StateScope,
    val model: PropertySheetModel,
    pageSize: Int,
    private val isHovered: (String) -> Boolean,
    private val onChanged: (PropertyField) -> Unit,
    val sheetStyle: PropertySheetViewStyle = PropertySheetViewStyle(),
    val style: PagedPropertySheetStyle = PagedPropertySheetStyle(),
) {
    val pagination = PaginationModel(scope, model.fields.size, pageSize)
    private val pages = SwitchNode<Int>("$id-pages") { pagination.pageIndex.value }
    val node = FlexNode(id, FlexDirection.COLUMN, crossAxis = CrossAxis.CENTER, gap = style.gap)

    init {
        require(model.fields.isNotEmpty()) { "A paged property sheet needs at least one field" }
        require(pageSize > 0) { "Property page size must be positive" }
        model.fields.chunked(pageSize).forEachIndexed { index, fields ->
            pages.putPage(
                index,
                PropertySheetView(
                    id = "$id-page-$index",
                    model = PropertySheetModel(fields),
                    isHovered = isHovered,
                    onChanged = onChanged,
                    style = sheetStyle,
                ).node,
            )
        }
        node.addChild(pages)
        node.addChild(PagerView(
            id = "$id-pager",
            pageIndex = { pagination.pageIndex.value },
            pageCount = { pagination.pageCount.value },
            visible = { style.controlsVisibleForSinglePage || pagination.pageCount.value > 1 },
            isHovered = isHovered,
            onPrevious = { pagination.previous() },
            onNext = { pagination.next() },
            style = PagerStyle(width = sheetStyle.width),
        ).node)
    }

    fun goToField(fieldId: String): Boolean {
        val index = model.fields.indexOfFirst { it.id == fieldId }
        if (index < 0) return false
        return pagination.goTo(index / pagination.pageSize.value)
    }
}
