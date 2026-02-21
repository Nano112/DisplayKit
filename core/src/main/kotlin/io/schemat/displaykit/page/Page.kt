package io.schemat.displaykit.page

import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.elements.PanelElement

class Page(
    val id: String,
    val ui: FloatingUI,
    val player: PlayerRef,
    var bounds: PageBounds,
    private var content: PageContent,
    var showChrome: Boolean = true
) {
    var container: PageContainer? = null
        internal set
    var manager: PageManager? = null
        internal set

    private var chrome: PageChrome? = null
    private var backgroundPanel: PanelElement? = null
    private val framePanels = mutableListOf<PanelElement>()
    private var isRendered = false
    private var isDestroyed = false
    private val frameThickness = 0.06f

    private val chromeHeight: Float
        get() = if (showChrome) PageChrome.CHROME_HEIGHT else 0f

    init {
        content.onAttached(this)
    }

    fun getContentBounds(): PageBounds {
        return if (showChrome) bounds.inset(0f, chromeHeight, 0f, 0f) else bounds
    }

    fun render() {
        if (isDestroyed) return
        renderBackground()
        if (showChrome) {
            chrome?.destroy()
            chrome = PageChrome(this, ui)
            chrome?.render(bounds)
        }
        content.rebuild()
        isRendered = true
    }

    private fun renderBackground() {
        backgroundPanel?.let { ui.removeElement(it) }
        framePanels.forEach { ui.removeElement(it) }
        framePanels.clear()

        backgroundPanel = ui.addPanel(
            offsetRight = bounds.centerX.toDouble(), offsetUp = bounds.centerY.toDouble(),
            offsetForward = 0.05, width = bounds.width, height = bounds.height,
            material = BlockStateRef.WHITE_CONCRETE
        )

        // Frame panels
        framePanels.add(ui.addPanel(
            offsetRight = bounds.centerX.toDouble(), offsetUp = (bounds.y + frameThickness / 2).toDouble(),
            offsetForward = 0.02, width = bounds.width + frameThickness * 2, height = frameThickness,
            material = BlockStateRef.BLACK_CONCRETE
        ))
        framePanels.add(ui.addPanel(
            offsetRight = bounds.centerX.toDouble(), offsetUp = (bounds.bottom - frameThickness / 2).toDouble(),
            offsetForward = 0.02, width = bounds.width + frameThickness * 2, height = frameThickness,
            material = BlockStateRef.BLACK_CONCRETE
        ))
        framePanels.add(ui.addPanel(
            offsetRight = (bounds.x - frameThickness / 2).toDouble(), offsetUp = bounds.centerY.toDouble(),
            offsetForward = 0.02, width = frameThickness, height = bounds.height,
            material = BlockStateRef.BLACK_CONCRETE
        ))
        framePanels.add(ui.addPanel(
            offsetRight = (bounds.right + frameThickness / 2).toDouble(), offsetUp = bounds.centerY.toDouble(),
            offsetForward = 0.02, width = frameThickness, height = bounds.height,
            material = BlockStateRef.BLACK_CONCRETE
        ))
    }

    fun resize(newBounds: PageBounds) {
        if (isDestroyed) return
        bounds = newBounds
        renderBackground()
        chrome?.destroy()
        if (showChrome) {
            chrome = PageChrome(this, ui)
            chrome?.render(bounds)
        }
        val contentBounds = getContentBounds()
        content.onResize(contentBounds.width, contentBounds.height)
    }

    fun setContent(newContent: PageContent) {
        content.onDetached()
        content = newContent
        content.onAttached(this)
        if (isRendered) content.rebuild()
    }

    fun getContent(): PageContent = content

    fun close() { manager?.closePage(this) ?: destroy() }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        chrome?.destroy(); chrome = null
        backgroundPanel?.let { ui.removeElement(it) }; backgroundPanel = null
        framePanels.forEach { ui.removeElement(it) }; framePanels.clear()
        content.onDetached()
        isRendered = false
    }

    internal fun detachFromContainer() { container = null }

    internal fun getDropdownOptions(): List<DropdownItem> {
        return manager?.getOptionsForPage(this) ?: emptyList()
    }
}
