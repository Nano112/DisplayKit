package io.schemat.displaykit.surface.layout

/**
 * A stack that measures every page but paints and hits only the selected one.
 *
 * Keeping every page in the tree gives tabbed compositions stable, natural
 * dimensions without application code rebuilding layout arithmetic whenever
 * selection changes.
 */
class SwitchNode<K>(
    id: String,
    private val selected: () -> K
) : BaseSurfaceNode(id), ChildViewport {
    private val pages = linkedMapOf<K, SurfaceNode>()

    init {
        flexGrow = 1
    }

    fun addPage(key: K, page: SurfaceNode) {
        require(key !in pages) { "duplicate switch page: $key" }
        pages[key] = page
        addChild(page)
    }

    /** Add or replace a page while retaining the switch node itself. */
    fun putPage(key: K, page: SurfaceNode) {
        pages[key] = page
        rebuildChildren()
    }

    /** Remove a page. Returns false when the key was already absent. */
    fun removePage(key: K): Boolean {
        if (pages.remove(key) == null) return false
        rebuildChildren()
        return true
    }

    fun containsPage(key: K): Boolean = key in pages

    fun pageKeys(): Set<K> = pages.keys.toSet()

    private fun rebuildChildren() {
        clearChildren()
        pages.values.forEach(::addChild)
    }

    override fun measureSelf(c: PxConstraints): PxSize {
        val inner = c.deflate(padding)
        var w = 0
        var h = 0
        for (page in pages.values) {
            val size = page.measure(inner)
            w = maxOf(w, size.w)
            h = maxOf(h, size.h)
        }
        return c.constrain(PxSize(w + padding.horizontal, h + padding.vertical))
    }

    override fun place(offset: PxOffset) {
        super.place(offset)
        val at = PxOffset(padding.left, padding.top)
        pages.values.forEach { it.place(at) }
    }

    override fun visibleChildren(): List<SurfaceNode> = listOfNotNull(pages[selected()])

    override fun hitTest(x: Int, y: Int): SurfaceNode? {
        if (!rect().contains(x, y)) return null
        return pages[selected()]?.hitTest(x, y) ?: this
    }
}
