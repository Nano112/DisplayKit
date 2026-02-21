package io.schemat.displaykit.page

import io.schemat.displaykit.ui.FloatingUI

enum class SplitDirection {
    HORIZONTAL,
    VERTICAL
}

sealed class PageOrContainer {
    data class SinglePage(val page: Page) : PageOrContainer()
    data class Nested(val container: PageContainer) : PageOrContainer()

    fun getPages(): List<Page> = when (this) {
        is SinglePage -> listOf(page)
        is Nested -> container.getPages()
    }

    fun render() {
        when (this) {
            is SinglePage -> page.render()
            is Nested -> container.render()
        }
    }

    fun resize(newBounds: PageBounds) {
        when (this) {
            is SinglePage -> page.resize(newBounds)
            is Nested -> container.resize(newBounds)
        }
    }

    fun destroy() {
        when (this) {
            is SinglePage -> page.destroy()
            is Nested -> container.destroy()
        }
    }
}

sealed class PageContainer(
    val id: String,
    val ui: FloatingUI,
    var bounds: PageBounds
) {
    var parent: PageContainer? = null
        internal set
    var manager: PageManager? = null
        internal set

    abstract fun getPages(): List<Page>
    abstract fun render()
    abstract fun resize(newBounds: PageBounds)
    abstract fun destroy()
    abstract fun removePage(page: Page): PageOrContainer?

    fun containsPage(page: Page): Boolean = getPages().contains(page)
}
