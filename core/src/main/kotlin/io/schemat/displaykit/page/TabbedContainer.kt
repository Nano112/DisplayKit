package io.schemat.displaykit.page

import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement
import io.schemat.displaykit.ui.elements.TabDefinition
import io.schemat.displaykit.ui.elements.TabsElement

class TabbedContainer(
    id: String,
    ui: FloatingUI,
    bounds: PageBounds,
    private val pages: MutableList<Page>
) : PageContainer(id, ui, bounds) {

    companion object {
        const val TAB_BAR_HEIGHT = 0.35f
        const val TAB_WIDTH = 0.9f
        const val TAB_GAP = 0.05f
    }

    private var selectedIndex: Int = 0
    private var tabBar: TabsElement? = null
    private val elements = mutableListOf<UIElement>()

    init { pages.forEach { it.container = this } }

    override fun getPages(): List<Page> = pages.toList()

    override fun render() {
        destroyElements()
        renderTabBar()
        val contentBounds = bounds.inset(0f, TAB_BAR_HEIGHT, 0f, 0f)
        pages.forEachIndexed { index, page ->
            page.resize(contentBounds)
            if (index == selectedIndex) page.render()
        }
    }

    override fun resize(newBounds: PageBounds) { bounds = newBounds; render() }

    override fun destroy() { destroyElements(); pages.forEach { it.destroy() }; pages.clear() }

    override fun removePage(page: Page): PageOrContainer? {
        val index = pages.indexOf(page)
        if (index < 0) return null
        page.detachFromContainer(); pages.removeAt(index)
        if (selectedIndex >= pages.size) selectedIndex = (pages.size - 1).coerceAtLeast(0)
        return when {
            pages.isEmpty() -> null
            pages.size == 1 -> PageOrContainer.SinglePage(pages.first())
            else -> { render(); null }
        }
    }

    fun selectTab(index: Int) {
        if (index < 0 || index >= pages.size || index == selectedIndex) return
        selectedIndex = index; render()
    }

    fun addPage(page: Page) { page.container = this; pages.add(page); render() }
    fun getSelectedPage(): Page? = pages.getOrNull(selectedIndex)
    fun getSelectedIndex(): Int = selectedIndex

    private fun renderTabBar() {
        val tabDefinitions = pages.map { TabDefinition(id = it.id, label = it.getContent().title) }
        val tabBarCenterX = bounds.centerX
        val tabBarCenterY = bounds.y - TAB_BAR_HEIGHT / 2

        tabBar = ui.addTabs(
            offsetRight = tabBarCenterX.toDouble(), offsetUp = tabBarCenterY.toDouble(),
            offsetForward = -0.02, tabs = tabDefinitions,
            tabWidth = TAB_WIDTH, tabHeight = TAB_BAR_HEIGHT - 0.05f,
            gap = TAB_GAP, selectedIndex = selectedIndex
        ) { index, _ -> selectTab(index) }
        elements.add(tabBar!!)
        tabBar!!.getTabButtons().forEach { elements.add(it) }
    }

    private fun destroyElements() {
        elements.forEach { it.destroy(); ui.removeElement(it) }
        elements.clear(); tabBar = null
    }
}
