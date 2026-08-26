package io.schemat.displaykit.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.UUID

class ActionMenuSessionTest {

    private fun actions(count: Int): List<ActionSpec> =
        (0 until count).map { ActionSpec("a$it", "Action $it") }

    @Test
    fun pageRequiresStableUniqueActionIds() {
        assertFailsWith<IllegalArgumentException> {
            ActionPage("root", listOf(ActionSpec("same", "One"), ActionSpec("same", "Two")))
        }
    }

    @Test
    fun pagingUsesOnlyVisibleActionsAndClampsAtBothEnds() {
        val page = ActionPage(
            "root",
            actions(7) + ActionSpec("hidden", "Hidden", visible = false)
        )
        val session = ActionMenuSession(page)

        assertEquals(listOf("a0", "a1", "a2"), session.window(3).actions.map { it.id })
        assertFalse(session.previousPage(3))
        assertTrue(session.nextPage(3))
        assertEquals(listOf("a3", "a4", "a5"), session.window(3).actions.map { it.id })
        assertTrue(session.nextPage(3))
        assertEquals(listOf("a6"), session.window(3).actions.map { it.id })
        assertFalse(session.nextPage(3))
    }

    @Test
    fun pushPopAndPopToRootOwnNavigationIndependentOfARenderer() {
        val session = ActionMenuSession(ActionPage("root", actions(1)))
        session.push(ActionPage("child", actions(2)))
        session.push(ActionPage("grandchild", actions(3)))
        assertEquals(3, session.depth)
        assertEquals("grandchild", session.currentPageId)

        assertTrue(session.pop())
        assertEquals("child", session.currentPageId)
        assertTrue(session.popToRoot())
        assertEquals(1, session.depth)
        assertEquals("root", session.currentPageId)

        assertTrue(session.pop())
        assertTrue(session.isClosed)
    }

    @Test
    fun focusIsStableByIdAndInvocationReportsWhyItDidNotRun() {
        var focuses = 0
        var invokes = 0
        val page = ActionPage(
            "root",
            listOf(
                ActionSpec("ready", "Ready", onFocus = ActionHandler { focuses++ }, onInvoke = ActionHandler { invokes++ }),
                ActionSpec("disabled", "Disabled", enabled = false),
                ActionSpec("busy", "Busy", busy = true),
                ActionSpec("hidden", "Hidden", visible = false)
            )
        )
        val session = ActionMenuSession(page)

        assertTrue(session.focus("ready"))
        assertFalse(session.focus("ready"))
        assertEquals(1, focuses)
        assertEquals(ActionInvocationResult.INVOKED, session.invoke("ready"))
        assertEquals(1, invokes)
        assertEquals(ActionInvocationResult.DISABLED, session.invoke("disabled"))
        assertEquals(ActionInvocationResult.BUSY, session.invoke("busy"))
        assertEquals(ActionInvocationResult.HIDDEN, session.invoke("hidden"))
        assertEquals(ActionInvocationResult.NOT_FOUND, session.invoke("missing"))
    }

    @Test
    fun callbacksReceiveRendererNeutralInteractionContext() {
        val actor = UUID.randomUUID()
        var received: ActionInteraction? = null
        val action = ActionSpec("run", "Run", onInvoke = ActionHandler { received = it.interaction })
        val session = ActionMenuSession(ActionPage("root", listOf(action)))
        val interaction = ActionInteraction(
            ActionSource.WORLD_SURFACE,
            ActionTrigger.SECONDARY_CLICK,
            actor
        )

        session.invoke("run", interaction)

        assertEquals(interaction, received)
    }

    @Test
    fun groupedPagesFlattenSectionsAndStillEnforcePageWideIdentity() {
        val page = ActionPage.grouped(
            "root",
            listOf(
                ActionGroup("editing", listOf(ActionSpec("move", "Move")), "Editing"),
                ActionGroup("danger", listOf(ActionSpec("remove", "Remove")), "Danger")
            )
        )
        assertEquals(listOf("move", "remove"), page.actions.map { it.id })
        assertEquals(listOf("editing", "danger"), page.groups.map { it.id })

        assertFailsWith<IllegalArgumentException> {
            ActionPage.grouped(
                "duplicate",
                listOf(
                    ActionGroup("one", listOf(ActionSpec("same", "One"))),
                    ActionGroup("two", listOf(ActionSpec("same", "Two")))
                )
            )
        }
    }

    @Test
    fun replacementPreservesFocusByIdentityAndNotifiesSubscribers() {
        val session = ActionMenuSession(ActionPage("root", actions(3)))
        val changes = mutableListOf<ActionMenuChange?>()
        val subscription = session.subscribe(ActionMenuListener { changes += it.change }, emitCurrent = true)
        session.focus("a1")

        session.replaceCurrent(
            ActionPage("root", listOf(ActionSpec("a1", "Updated"), ActionSpec("new", "New")))
        )
        assertEquals("a1", session.focusedActionId)

        session.replaceCurrent(ActionPage("root", listOf(ActionSpec("new", "New"))))
        assertNull(session.focusedActionId)
        assertEquals(listOf(null, ActionMenuChange.FOCUS, ActionMenuChange.REPLACE, ActionMenuChange.REPLACE), changes)

        subscription.close()
        session.close()
        assertEquals(4, changes.size, "closed subscription must not receive later changes")
    }

    @Test
    fun replacementPreservesAValidPageAndClampsOnlyWhenWindowed() {
        val session = ActionMenuSession(ActionPage("root", actions(14)))
        session.nextPage(6)
        assertEquals(1, session.window(6).pageIndex)

        session.replaceCurrent(ActionPage("root", actions(13)))
        assertEquals(1, session.window(6).pageIndex)

        session.replaceCurrent(ActionPage("root", actions(2)))
        assertEquals(0, session.window(6).pageIndex)
    }

    @Test
    fun submenuConveniencePushesAChildPage() {
        val submenu = ActionSpec.submenu("open", "Open") {
            ActionPage("child", listOf(ActionSpec("inside", "Inside")))
        }
        val session = ActionMenuSession(ActionPage("root", listOf(submenu)))

        assertEquals(ActionInvocationResult.INVOKED, session.invoke("open"))
        assertEquals("child", session.currentPageId)
        assertEquals(2, session.depth)
    }
}
