package io.schemat.displaykit.page

import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.platform.TaskHandle
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.TextComponent
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.elements.GrabberElement

enum class SplitPosition { FIRST, SECOND }
enum class PageInteractionMode { NONE, MOVE, RESIZE }

class PageManager(
    val ui: FloatingUI,
    val player: PlayerRef,
    private var totalBounds: PageBounds
) {
    private var root: PageOrContainer? = null
    private val pageRegistry = mutableMapOf<String, Page>()
    private val containerRegistry = mutableMapOf<String, PageContainer>()
    private var updateTask: TaskHandle? = null
    private var isDestroyed = false
    private var interactionMode = PageInteractionMode.NONE
    private var interactionPage: Page? = null
    private val interactionHandles = mutableListOf<GrabberElement>()
    private var moveOffset: Pair<Float, Float>? = null
    private var resizeEdge: ResizeEdge? = null
    private var originalBounds: PageBounds? = null
    private val navigationStack = mutableListOf<PageContent>()
    private var currentPage: Page? = null

    init { startUpdateTask() }

    enum class ResizeEdge {
        TOP, BOTTOM, LEFT, RIGHT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    fun createPage(content: PageContent, bounds: PageBounds? = null, showChrome: Boolean = true): Page {
        val pageBounds = bounds ?: totalBounds
        val page = Page(
            id = "page_${System.currentTimeMillis()}_${pageRegistry.size}",
            ui = ui, player = player, bounds = pageBounds, content = content, showChrome = showChrome
        )
        page.manager = this
        pageRegistry[page.id] = page
        if (root == null) root = PageOrContainer.SinglePage(page)
        return page
    }

    fun showPage(content: PageContent, showChrome: Boolean = true): Page {
        root?.destroy(); root = null
        pageRegistry.clear(); containerRegistry.clear(); navigationStack.clear()
        val page = createPage(content, totalBounds, showChrome)
        root = PageOrContainer.SinglePage(page)
        currentPage = page
        navigationStack.add(content)
        page.render()
        return page
    }

    fun navigateTo(content: PageContent) {
        val page = currentPage ?: return
        navigationStack.add(content)
        page.setContent(content)
        page.render()
    }

    fun navigateBack(): Boolean {
        if (navigationStack.size <= 1) return false
        val page = currentPage ?: return false
        navigationStack.removeAt(navigationStack.size - 1)
        page.setContent(navigationStack.last())
        page.render()
        return true
    }

    fun canNavigateBack(): Boolean = navigationStack.size > 1
    fun getNavigationDepth(): Int = navigationStack.size

    fun splitPage(
        page: Page, direction: SplitDirection, newContent: PageContent,
        position: SplitPosition = SplitPosition.SECOND, ratio: Float = 0.5f
    ): SplitContainer {
        val currentBounds = page.bounds
        val oldContainer = page.container
        page.destroy()

        val newPage = Page(
            id = "page_${System.currentTimeMillis()}_${pageRegistry.size}",
            ui = ui, player = player, bounds = currentBounds,
            content = newContent, showChrome = page.showChrome
        )
        newPage.manager = this
        pageRegistry[newPage.id] = newPage

        val (first, second) = if (position == SplitPosition.FIRST) {
            Pair(PageOrContainer.SinglePage(newPage), PageOrContainer.SinglePage(page))
        } else {
            Pair(PageOrContainer.SinglePage(page), PageOrContainer.SinglePage(newPage))
        }

        val newContainer = SplitContainer(
            id = "split_${System.currentTimeMillis()}", ui = ui, bounds = currentBounds,
            direction = direction, first = first, second = second, splitRatio = ratio
        )
        newContainer.manager = this
        containerRegistry[newContainer.id] = newContainer
        replaceInTreeWithOldContainer(page, oldContainer, newContainer)
        newContainer.render()
        return newContainer
    }

    fun mergeToTabs(page1: Page, page2: Page): TabbedContainer {
        val bounds = page1.bounds
        val container = TabbedContainer(
            id = "tabs_${System.currentTimeMillis()}", ui = ui, bounds = bounds,
            pages = mutableListOf(page1, page2)
        )
        container.manager = this
        containerRegistry[container.id] = container
        replaceInTree(page1, container)
        if (page2.container != null && page2.container != page1.container) page2.container?.removePage(page2)
        container.render()
        return container
    }

    fun closePage(page: Page) {
        val container = page.container
        if (container == null) {
            page.destroy(); pageRegistry.remove(page.id)
            if (root is PageOrContainer.SinglePage && (root as PageOrContainer.SinglePage).page == page) root = null
        } else {
            val remaining = container.removePage(page)
            page.destroy(); pageRegistry.remove(page.id)
            if (remaining != null) replaceInTree(container, remaining)
        }
    }

    fun getOptionsForPage(page: Page): List<DropdownItem> {
        val options = mutableListOf<DropdownItem>()
        if (page.container == null) {
            options.add(DropdownItem(id = "resize", label = "Resize", icon = "\u2922") { enterResizeMode(page) })
            options.add(DropdownItem(id = "move", label = "Move", icon = "\u2725") { enterMoveMode(page) })
        }
        return options
    }

    private fun replaceInTree(old: Page, new: PageContainer) {
        replaceInTreeWithOldContainer(old, old.container, new)
    }

    private fun replaceInTreeWithOldContainer(old: Page, oldContainer: PageContainer?, new: PageContainer) {
        if (oldContainer == null) {
            root = PageOrContainer.Nested(new)
        } else {
            when (oldContainer) {
                is SplitContainer -> {
                    if (oldContainer.isInFirstPosition(old)) oldContainer.setFirst(PageOrContainer.Nested(new))
                    else oldContainer.setSecond(PageOrContainer.Nested(new))
                    new.parent = oldContainer
                }
                is TabbedContainer -> {}
            }
        }
    }

    private fun replaceInTree(old: PageContainer, new: PageOrContainer) {
        val parent = old.parent
        if (parent == null) {
            root = new; containerRegistry.remove(old.id)
        } else {
            when (parent) {
                is SplitContainer -> {
                    if (parent.getPages().firstOrNull()?.container == old) parent.setFirst(new)
                    else parent.setSecond(new)
                }
                is TabbedContainer -> {}
            }
            containerRegistry.remove(old.id)
        }
    }

    fun enterMoveMode(page: Page) {
        exitInteractionMode()
        interactionMode = PageInteractionMode.MOVE
        interactionPage = page
        val bounds = page.bounds
        var grabber: GrabberElement? = null
        grabber = ui.addGrabber(
            offsetRight = bounds.centerX.toDouble(), offsetUp = bounds.centerY.toDouble(),
            offsetForward = -0.1, material = BlockStateRef.LIGHT_BLUE_CONCRETE,
            hoverMaterial = BlockStateRef.YELLOW_CONCRETE, size = 0.2f, visible = true
        ) { if (grabber?.isToggled == false) exitInteractionMode() }
        interactionHandles.add(grabber)
        player.sendMessage(TextComponent.of("Click the handle to grab, move, then click again to confirm."))
    }

    fun enterResizeMode(page: Page) {
        exitInteractionMode()
        interactionMode = PageInteractionMode.RESIZE
        interactionPage = page
        originalBounds = page.bounds
        val bounds = page.bounds
        val handleSize = 0.12f
        val z = -0.1

        val corners = listOf(
            Triple(bounds.x, bounds.y, ResizeEdge.TOP_LEFT),
            Triple(bounds.right, bounds.y, ResizeEdge.TOP_RIGHT),
            Triple(bounds.x, bounds.bottom, ResizeEdge.BOTTOM_LEFT),
            Triple(bounds.right, bounds.bottom, ResizeEdge.BOTTOM_RIGHT)
        )
        for ((x, y, edge) in corners) {
            var grabber: GrabberElement? = null
            grabber = ui.addGrabber(
                offsetRight = x.toDouble(), offsetUp = y.toDouble(), offsetForward = z,
                material = BlockStateRef.ORANGE_CONCRETE, hoverMaterial = BlockStateRef.YELLOW_CONCRETE,
                size = handleSize, visible = true
            ) {
                if (grabber?.isToggled == true) { resizeEdge = edge; originalBounds = page.bounds }
                else { resizeEdge = null; exitInteractionMode() }
            }
            interactionHandles.add(grabber)
        }

        val edges = listOf(
            Triple(bounds.centerX, bounds.y, ResizeEdge.TOP),
            Triple(bounds.centerX, bounds.bottom, ResizeEdge.BOTTOM),
            Triple(bounds.x, bounds.centerY, ResizeEdge.LEFT),
            Triple(bounds.right, bounds.centerY, ResizeEdge.RIGHT)
        )
        for ((x, y, edge) in edges) {
            var grabber: GrabberElement? = null
            grabber = ui.addGrabber(
                offsetRight = x.toDouble(), offsetUp = y.toDouble(), offsetForward = z,
                material = BlockStateRef.CYAN_CONCRETE, hoverMaterial = BlockStateRef.YELLOW_CONCRETE,
                size = handleSize * 0.8f, visible = true
            ) {
                if (grabber?.isToggled == true) { resizeEdge = edge; originalBounds = page.bounds }
                else { resizeEdge = null; exitInteractionMode() }
            }
            interactionHandles.add(grabber)
        }

        player.sendMessage(TextComponent.of("Click a handle to grab, drag to resize, then click again to confirm."))
    }

    fun exitInteractionMode() {
        for (handle in interactionHandles) { handle.destroy(); ui.removeElement(handle) }
        interactionHandles.clear()
        interactionMode = PageInteractionMode.NONE
        interactionPage = null; moveOffset = null; resizeEdge = null; originalBounds = null
    }

    private fun startUpdateTask() {
        updateTask = ui.platform.scheduler.scheduleRepeating(0L, 1L, Runnable {
            if (isDestroyed) return@Runnable
            updateDividers(); updateInteractionMode()
        })
    }

    private fun updateDividers() {
        for (container in containerRegistry.values) {
            if (container is SplitContainer) container.getDivider()?.update()
        }
    }

    private fun updateInteractionMode() {
        val page = interactionPage ?: return
        when (interactionMode) {
            PageInteractionMode.MOVE -> updateMoveMode(page)
            PageInteractionMode.RESIZE -> updateResizeMode(page)
            PageInteractionMode.NONE -> {}
        }
    }

    private fun updateMoveMode(page: Page) {
        val grabber = interactionHandles.firstOrNull() ?: return
        if (grabber.isToggled) {
            val mousePos = ui.getMousePositionOnPlane() ?: return
            val newBounds = PageBounds(
                x = mousePos.first.toFloat() - page.bounds.width / 2,
                y = mousePos.second.toFloat() + page.bounds.height / 2,
                width = page.bounds.width, height = page.bounds.height
            )
            page.resize(newBounds)
            grabber.setPosition(newBounds.centerX.toDouble(), newBounds.centerY.toDouble())
        }
    }

    private fun updateResizeMode(page: Page) {
        val activeEdge = resizeEdge ?: return
        val original = originalBounds ?: return
        val activeGrabber = interactionHandles.find { it.isToggled } ?: return
        val mousePos = ui.getMousePositionOnPlane() ?: return
        val mouseX = mousePos.first.toFloat(); val mouseY = mousePos.second.toFloat()
        var newX = original.x; var newY = original.y
        var newWidth = original.width; var newHeight = original.height
        val minSize = 0.5f

        when (activeEdge) {
            ResizeEdge.LEFT -> { newX = mouseX; newWidth = (original.width - (mouseX - original.x)).coerceAtLeast(minSize) }
            ResizeEdge.RIGHT -> { newWidth = (mouseX - original.x).coerceAtLeast(minSize) }
            ResizeEdge.TOP -> { newY = mouseY; newHeight = (original.height + (mouseY - original.y)).coerceAtLeast(minSize) }
            ResizeEdge.BOTTOM -> { newHeight = (original.height + (original.bottom - mouseY)).coerceAtLeast(minSize) }
            ResizeEdge.TOP_LEFT -> {
                newX = mouseX; newY = mouseY
                newWidth = (original.width - (mouseX - original.x)).coerceAtLeast(minSize)
                newHeight = (original.height + (mouseY - original.y)).coerceAtLeast(minSize)
            }
            ResizeEdge.TOP_RIGHT -> {
                newY = mouseY
                newWidth = (mouseX - original.x).coerceAtLeast(minSize)
                newHeight = (original.height + (mouseY - original.y)).coerceAtLeast(minSize)
            }
            ResizeEdge.BOTTOM_LEFT -> {
                newX = mouseX
                newWidth = (original.width - (mouseX - original.x)).coerceAtLeast(minSize)
                newHeight = (original.y - mouseY + original.height).coerceAtLeast(minSize)
            }
            ResizeEdge.BOTTOM_RIGHT -> {
                newWidth = (mouseX - original.x).coerceAtLeast(minSize)
                newHeight = (original.y - mouseY + original.height).coerceAtLeast(minSize)
            }
        }

        val newBounds = PageBounds(newX, newY, newWidth, newHeight)
        page.resize(newBounds)
        updateResizeHandlePositions(newBounds)
    }

    private fun updateResizeHandlePositions(bounds: PageBounds) {
        if (interactionHandles.size < 8) return
        interactionHandles[0].setPosition(bounds.x.toDouble(), bounds.y.toDouble())
        interactionHandles[1].setPosition(bounds.right.toDouble(), bounds.y.toDouble())
        interactionHandles[2].setPosition(bounds.x.toDouble(), bounds.bottom.toDouble())
        interactionHandles[3].setPosition(bounds.right.toDouble(), bounds.bottom.toDouble())
        interactionHandles[4].setPosition(bounds.centerX.toDouble(), bounds.y.toDouble())
        interactionHandles[5].setPosition(bounds.centerX.toDouble(), bounds.bottom.toDouble())
        interactionHandles[6].setPosition(bounds.x.toDouble(), bounds.centerY.toDouble())
        interactionHandles[7].setPosition(bounds.right.toDouble(), bounds.centerY.toDouble())
    }

    fun getPages(): List<Page> = pageRegistry.values.toList()
    fun getPage(id: String): Page? = pageRegistry[id]

    fun resize(newBounds: PageBounds) { totalBounds = newBounds; root?.resize(newBounds); root?.render() }

    fun destroy() {
        if (isDestroyed) return; isDestroyed = true
        updateTask?.cancel(); updateTask = null
        root?.destroy(); root = null
        pageRegistry.clear(); containerRegistry.clear()
    }
}
