package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.action.ActionHandler
import io.schemat.displaykit.action.ActionIcon
import io.schemat.displaykit.action.ActionMenuSession
import io.schemat.displaykit.action.ActionPage
import io.schemat.displaykit.action.ActionSpec
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.surface.PointerButton
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.layout.SurfaceNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActionMenuViewTest {

    @Test
    fun stableRowsDriveFocusInvocationPagingAndClose() {
        var invoked = 0
        var invalidations = 0
        val actions = (0 until 8).map { index ->
            ActionSpec(
                id = "a$index",
                label = "Action $index",
                onInvoke = ActionHandler { invoked++ }
            )
        }
        val session = ActionMenuSession(ActionPage("root", actions, "Actions"))
        val view = ActionMenuView(
            id = "menu",
            actions = session,
            isHovered = { false },
            onStateChanged = { invalidations++ },
            style = ActionMenuViewStyle(pageSize = 6)
        )
        val surface = Surface(240, 260, Vec3d.ZERO, 3f).also { it.renderMode = RenderMode.ENTITIES }
        surface.layout { it.addChild(view.node) }

        val first = assertNotNull(surface.root?.find("menu-action-0"))
        surface.dispatch(SurfaceEvent.PointerEnter(first.rect().x + 1, first.rect().y + 1), first)
        assertEquals("a0", session.focusedActionId)
        surface.dispatch(
            SurfaceEvent.Click(first.rect().x + 1, first.rect().y + 1, PointerButton.RIGHT),
            first
        )
        assertEquals(1, invoked)

        val next = assertNotNull(surface.root?.find("menu-next"))
        surface.dispatch(
            SurfaceEvent.Click(next.rect().x + 1, next.rect().y + 1, PointerButton.LEFT),
            next
        )
        assertEquals(1, session.window(6).pageIndex)
        assertEquals("a6", session.window(6).actions.first().id)

        val back = assertNotNull(surface.root?.find("menu-back"))
        surface.dispatch(
            SurfaceEvent.Click(back.rect().x + 1, back.rect().y + 1, PointerButton.LEFT),
            back
        )
        assertTrue(session.isClosed)
        assertTrue(invalidations >= 4)
    }

    @Test
    fun unavailableNavigationAndEmptyRowsPassInsteadOfActing() {
        val session = ActionMenuSession(ActionPage("root", listOf(ActionSpec("only", "Only"))))
        val view = ActionMenuView("menu", session, { false }, {})
        val surface = Surface(240, 260, Vec3d.ZERO, 3f)
        surface.layout { it.addChild(view.node) }

        val empty = assertNotNull(surface.root?.find("menu-action-1"))
        val previous = assertNotNull(surface.root?.find("menu-previous"))
        val next = assertNotNull(surface.root?.find("menu-next"))

        assertEquals(null, surface.dispatch(
            SurfaceEvent.Click(empty.rect().x + 1, empty.rect().y + 1, PointerButton.LEFT),
            empty
        ))
        assertEquals(null, surface.dispatch(
            SurfaceEvent.Click(previous.rect().x + 1, previous.rect().y + 1, PointerButton.LEFT),
            previous
        ))
        assertEquals(null, surface.dispatch(
            SurfaceEvent.Click(next.rect().x + 1, next.rect().y + 1, PointerButton.LEFT),
            next
        ))
        assertFalse(session.isClosed)
    }

    @Test
    fun surfaceActionRendererUsesTheActionSpriteRepresentation() {
        SpriteGlyphs.clear()
        val icon = SpriteId("items", "item/wheat")
        val session = ActionMenuSession(
            ActionPage("root", listOf(ActionSpec("farm", "Farm", ActionIcon.Sprite(icon))))
        )
        val view = ActionMenuView("menu", session, { false }, {})
        val surface = Surface(240, 260, Vec3d.ZERO, 3f).also {
            it.renderMode = RenderMode.COMPOSITED
            it.layout { root -> root.addChild(view.node) }
            it.paintTree()
        }
        assertTrue(SpriteGlyphs.requested().any { it.entry.id == icon })
        SpriteGlyphs.clear()
    }

    private fun SurfaceNode.find(id: String): SurfaceNode? {
        if (this.id == id) return this
        return children.firstNotNullOfOrNull { it.find(id) }
    }
}
