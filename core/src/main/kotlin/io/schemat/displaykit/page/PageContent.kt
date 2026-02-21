package io.schemat.displaykit.page

import io.schemat.displaykit.layout.Layout
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement

abstract class PageContent(
    val id: String,
    val title: String = id
) {
    var page: Page? = null
        internal set

    protected val elements = mutableListOf<UIElement>()
    protected var layout: Layout? = null

    abstract fun buildLayout(width: Float, height: Float): Layout
    abstract fun render(ui: FloatingUI, renderer: LayoutRenderer, bounds: PageBounds)

    open fun onResize(newWidth: Float, newHeight: Float) { rebuild() }
    open fun onStateChanged() { rebuild() }

    fun rebuild() {
        val p = page ?: return
        destroy()
        val contentBounds = p.getContentBounds()
        layout = buildLayout(contentBounds.width, contentBounds.height)
        val renderer = LayoutRenderer(p.ui, layout!!, contentBounds)
        render(p.ui, renderer, contentBounds)
    }

    open fun destroy() {
        elements.forEach { element ->
            element.destroy()
            page?.ui?.removeElement(element)
        }
        elements.clear()
        layout = null
    }

    open fun onAttached(page: Page) { this.page = page }
    open fun onDetached() { destroy(); this.page = null }
}

class SimpleContent(
    id: String,
    title: String = id,
    private val material: BlockStateRef = BlockStateRef.GRAY_CONCRETE
) : PageContent(id, title) {

    override fun buildLayout(width: Float, height: Float): Layout {
        return Layout(width = width, height = height).apply {
            build { leaf("background", width = width, height = height) }
            compute()
        }
    }

    override fun render(ui: FloatingUI, renderer: LayoutRenderer, bounds: PageBounds) {
        renderer.renderPanel("background", material)?.let { elements.add(it) }
        if (bounds.height > 0.5f) {
            ui.addLabel(
                offsetRight = bounds.centerX.toDouble(),
                offsetUp = (bounds.y - 0.2).toDouble(),
                offsetForward = -0.05, text = title, scale = 0.4f
            ).let { elements.add(it) }
        }
    }
}
