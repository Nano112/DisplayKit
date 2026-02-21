package io.schemat.displaykit.page

import io.schemat.displaykit.ui.FloatingUI

class SplitContainer(
    id: String,
    ui: FloatingUI,
    bounds: PageBounds,
    val direction: SplitDirection,
    private var first: PageOrContainer,
    private var second: PageOrContainer,
    private var splitRatio: Float = 0.5f
) : PageContainer(id, ui, bounds) {

    companion object {
        const val DIVIDER_THICKNESS = 0.08f
        const val MIN_RATIO = 0.15f
        const val MAX_RATIO = 0.85f
    }

    private var divider: DividerElement? = null

    init { updateChildParents() }

    fun getSplitRatio(): Float = splitRatio

    fun setSplitRatio(ratio: Float) {
        splitRatio = ratio.coerceIn(MIN_RATIO, MAX_RATIO)
        render()
    }

    override fun getPages(): List<Page> = first.getPages() + second.getPages()

    override fun render() {
        divider?.destroy()
        val (firstBounds, secondBounds) = calculateChildBounds()
        resizeChild(first, firstBounds); first.render()
        resizeChild(second, secondBounds); second.render()
        renderDivider()
    }

    override fun resize(newBounds: PageBounds) { bounds = newBounds }

    private fun resizeChild(child: PageOrContainer, newBounds: PageBounds) {
        when (child) {
            is PageOrContainer.SinglePage -> child.page.bounds = newBounds
            is PageOrContainer.Nested -> child.container.bounds = newBounds
        }
    }

    override fun destroy() {
        divider?.destroy(); divider = null
        first.destroy(); second.destroy()
    }

    override fun removePage(page: Page): PageOrContainer? {
        when (val f = first) {
            is PageOrContainer.SinglePage -> if (f.page == page) { f.page.detachFromContainer(); return second }
            is PageOrContainer.Nested -> if (f.container.containsPage(page)) {
                val remaining = f.container.removePage(page)
                if (remaining == null) return second
                else { first = remaining; render(); return null }
            }
        }
        when (val s = second) {
            is PageOrContainer.SinglePage -> if (s.page == page) { s.page.detachFromContainer(); return first }
            is PageOrContainer.Nested -> if (s.container.containsPage(page)) {
                val remaining = s.container.removePage(page)
                if (remaining == null) return first
                else { second = remaining; render(); return null }
            }
        }
        return null
    }

    private fun calculateChildBounds(): Pair<PageBounds, PageBounds> = when (direction) {
        SplitDirection.HORIZONTAL -> bounds.splitHorizontal(splitRatio, DIVIDER_THICKNESS)
        SplitDirection.VERTICAL -> bounds.splitVertical(splitRatio, DIVIDER_THICKNESS)
    }

    private fun renderDivider() {
        val (firstBounds, _) = calculateChildBounds()
        val dividerBounds = when (direction) {
            SplitDirection.HORIZONTAL -> PageBounds(firstBounds.right, bounds.y, DIVIDER_THICKNESS, bounds.height)
            SplitDirection.VERTICAL -> PageBounds(bounds.x, firstBounds.bottom, bounds.width, DIVIDER_THICKNESS)
        }
        divider = DividerElement(ui = ui, container = this, direction = direction, bounds = dividerBounds)
        divider?.spawn()
    }

    private fun updateChildParents() {
        when (val f = first) {
            is PageOrContainer.SinglePage -> f.page.container = this
            is PageOrContainer.Nested -> f.container.parent = this
        }
        when (val s = second) {
            is PageOrContainer.SinglePage -> s.page.container = this
            is PageOrContainer.Nested -> s.container.parent = this
        }
    }

    fun setFirst(child: PageOrContainer) { first = child; updateChildParents() }
    fun setSecond(child: PageOrContainer) { second = child; updateChildParents() }
    fun getDivider(): DividerElement? = divider
    fun getFirst(): PageOrContainer = first
    fun getSecond(): PageOrContainer = second
    fun isInFirstPosition(page: Page): Boolean = when (val f = first) {
        is PageOrContainer.SinglePage -> f.page == page
        is PageOrContainer.Nested -> f.container.containsPage(page)
    }
}
