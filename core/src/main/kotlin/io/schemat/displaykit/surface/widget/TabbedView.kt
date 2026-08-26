package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.SurfaceNode
import io.schemat.displaykit.surface.layout.SwitchNode

data class TabbedPage<T>(
    val value: T,
    val label: String,
    val content: SurfaceNode
)

data class TabbedViewStyle(
    val gap: Int = 10,
    val tabs: BlockTabStripStyle = BlockTabStripStyle()
)

/**
 * General-purpose vertical tabs plus a naturally sized selected page.
 * Selection is state-driven; the strip and page switch read the same source
 * so their visual and interactive states cannot drift apart.
 */
class TabbedView<T>(
    id: String,
    pages: List<TabbedPage<T>>,
    selected: () -> T,
    isHovered: (String) -> Boolean,
    onSelected: (T) -> Unit,
    style: TabbedViewStyle = TabbedViewStyle()
) {
    val node = FlexNode(id, FlexDirection.ROW, gap = style.gap)
    val pageStack = SwitchNode("$id-pages", selected)

    init {
        require(pages.isNotEmpty()) { "a tabbed view needs at least one page" }
        require(pages.map { it.value }.distinct().size == pages.size) { "tab values must be unique" }

        val labels = pages.associate { it.value to it.label }
        val strip = BlockTabStrip(
            id = "$id-tabs",
            values = pages.map { it.value },
            selected = selected,
            label = { labels.getValue(it) },
            isHovered = isHovered,
            onSelected = onSelected,
            style = style.tabs
        )
        pages.forEach { pageStack.addPage(it.value, it.content) }
        node.flexGrow = 1
        node.addChild(strip.node)
        node.addChild(pageStack)
    }
}
