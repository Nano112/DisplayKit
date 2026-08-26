package io.schemat.displaykit.surface.terminal

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.layout.BoxNode
import io.schemat.displaykit.surface.layout.PxConstraints
import io.schemat.displaykit.surface.layout.PxOffset
import io.schemat.displaykit.surface.layout.SurfaceNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalWidgetTest {

    private val width = 200
    private val height = 120

    /**
     * Mirrors what [io.schemat.displaykit.surface.Surface.layout] does around
     * a builder lambda: build the tree under a fresh root, then measure and
     * place it -- [TerminalWidget.build] itself runs before real geometry
     * exists, exactly like the real surface's builder lambda does.
     */
    private fun buildAndPlace(widget: TerminalWidget): SurfaceNode {
        val root = BoxNode("root")
        widget.build(root, title = "Terminal", promptHint = "type...", onClose = {})
        root.measure(PxConstraints.exactly(width, height))
        root.place(PxOffset.Zero)
        widget.afterLayout()
        return root
    }

    @Test
    fun stepPxEqualsRowHeight() {
        val model = TerminalModel(columns = 40)
        repeat(30) { model.append("line $it") }
        val widget = TerminalWidget(model, contentWidth = width - 20, rowHeightPx = 12)
        buildAndPlace(widget)
        assertEquals(12, widget.pane.stepPx, "stepPx must match the row pitch or rows vanish")
        // Every emitted row really did measure to that same height.
        for (row in widget.pane.children) {
            assertEquals(12, row.rect().h, "row '${row.id}' is not uniform height")
        }
    }

    @Test
    fun `title body and prompt remain inside the terminal bounds`() {
        val widget = TerminalWidget(
            TerminalModel(columns = 40),
            contentWidth = width - 20,
            rowHeightPx = 10,
            titleBarHeight = 30,
            padding = 10
        )
        val root = buildAndPlace(widget)
        val prompt = root.find("terminal-prompt") ?: error("prompt not built")

        assertTrue(prompt.rect().h > 0)
        assertTrue(prompt.rect().y >= root.rect().y)
        assertTrue(prompt.rect().bottom <= root.rect().bottom)
    }

    @Test
    fun `prompt and visible history are emitted by the composed renderer`() {
        val model = TerminalModel(columns = 40)
        repeat(20) { model.append("line $it") }
        val widget = TerminalWidget(
            model,
            contentWidth = width - 20,
            rowHeightPx = 10,
            titleBarHeight = 30,
            padding = 10
        )
        val surface = Surface(width, height, Vec3d.ZERO, 2f)
        surface.layout { root ->
            widget.build(root, "Terminal", "type...", onClose = {})
        }
        widget.afterLayout()
        surface.paintTree()

        val emitted = surface.toEntities()
            .filterIsInstance<VirtualTextDisplay>()
            .joinToString("\n") { it.text.plain() }
        assertTrue(emitted.contains("> type..."), "fixed prompt was not painted")
        assertTrue(emitted.contains("line 19"), "bottom-pinned history was not painted")
    }

    @Test
    fun appendingWhileAtTheBottomKeepsTheViewAtTheBottom() {
        val model = TerminalModel(columns = 40)
        repeat(20) { model.append("line $it") }
        val widget = TerminalWidget(model, contentWidth = width - 20, rowHeightPx = 10)
        buildAndPlace(widget)
        assertTrue(widget.pane.maxScroll() > 0, "test needs content taller than the viewport")
        assertEquals(widget.pane.maxScroll(), widget.pane.scrollPx, "fresh terminal starts pinned to the bottom")

        model.append("a brand new line")
        buildAndPlace(widget)

        assertEquals(widget.pane.maxScroll(), widget.pane.scrollPx, "still pinned to the (new) bottom")
    }

    @Test
    fun appendingWhileScrolledAwayDoesNotMoveTheView() {
        val model = TerminalModel(columns = 40)
        repeat(20) { model.append("line $it") }
        val widget = TerminalWidget(model, contentWidth = width - 20, rowHeightPx = 10)
        buildAndPlace(widget)

        // Scroll away from the bottom, the way the wired handlers would.
        widget.pane.scrollBy(-2)
        widget.noteUserScroll()
        assertTrue(widget.scrolledAway)
        val scrolledPosition = widget.pane.scrollPx
        assertTrue(scrolledPosition < widget.pane.maxScroll(), "test needs to actually be away from the bottom")

        model.append("a brand new line")
        buildAndPlace(widget)

        assertEquals(scrolledPosition, widget.pane.scrollPx, "the view must not move out from under the user")
        // And definitely not silently snapped back to the top either.
        assertTrue(widget.pane.scrollPx > 0)
    }

    @Test
    fun scrollingBackToTheBottomReArmsAutoFollow() {
        val model = TerminalModel(columns = 40)
        repeat(20) { model.append("line $it") }
        val widget = TerminalWidget(model, contentWidth = width - 20, rowHeightPx = 10)
        buildAndPlace(widget)

        widget.pane.scrollBy(-2)
        widget.noteUserScroll()
        assertTrue(widget.scrolledAway)

        // Scroll all the way back down.
        widget.pane.scrollTo(widget.pane.maxScroll())
        widget.noteUserScroll()
        assertTrue(!widget.scrolledAway, "back at the bottom re-arms auto-follow")

        model.append("another new line")
        buildAndPlace(widget)
        assertEquals(widget.pane.maxScroll(), widget.pane.scrollPx)
    }

    private fun SurfaceNode.find(wanted: String): SurfaceNode? =
        if (id == wanted) this else children.firstNotNullOfOrNull { it.find(wanted) }
}
