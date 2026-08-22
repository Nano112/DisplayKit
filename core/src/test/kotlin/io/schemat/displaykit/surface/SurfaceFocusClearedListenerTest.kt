package io.schemat.displaykit.surface

import io.schemat.displaykit.surface.layout.BoxNode
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hook that lets platform state die with the focus that armed it.
 *
 * Fabric's hotbar-scroll capture remembers a player's real selected slot for
 * as long as the pointer sits inside a scrollable. Nothing used to tell it
 * when that stopped being true, so a viewer who disconnected mid-hover left an
 * entry keyed by their UUID forever, and on reconnect their first scroll was
 * measured against a slot from the previous session.
 */
class SurfaceFocusClearedListenerTest {

    private val registered = mutableListOf<SurfaceFocus.FocusClearedListener>()

    private fun listener(body: (UUID) -> Unit): SurfaceFocus.FocusClearedListener {
        val l = SurfaceFocus.FocusClearedListener { body(it) }
        SurfaceFocus.onFocusCleared(l)
        registered += l
        return l
    }

    @AfterTest
    fun tearDown() {
        // SurfaceFocus is a singleton; a listener left behind would fire
        // during unrelated tests.
        registered.forEach { SurfaceFocus.removeFocusClearedListener(it) }
        registered.clear()
    }

    @Test
    fun clearNotifiesListenersWithThatViewer() {
        val seen = mutableListOf<UUID>()
        listener { seen += it }

        val a = UUID.randomUUID()
        SurfaceFocus.clear(a)

        assertEquals(listOf(a), seen)
    }

    @Test
    fun listenersSeeFocusAlreadyGone() {
        // A listener that asks "is this viewer still hovering something?" must
        // get null. Notifying before removal would have it tear down state the
        // viewer appears to still be using.
        val id = UUID.randomUUID()
        SurfaceFocus.pointerAt(id, BoxNode("hovered-node"))
        assertTrue(SurfaceFocus.hovered(id) != null, "setup must actually establish focus")

        var hoveredDuringCallback: Any? = "unset"
        listener { hoveredDuringCallback = SurfaceFocus.hovered(it) }

        SurfaceFocus.clear(id)

        assertEquals(null, hoveredDuringCallback, "focus must already be dropped when listeners run")
    }

    @Test
    fun aThrowingListenerDoesNotStrandTheOthersOrTheCaller() {
        // Teardown runs on disconnect. One bad listener must not abort the
        // rest of it -- that would leak exactly what this hook exists to free.
        val reached = mutableListOf<String>()
        listener { reached += "first" }
        listener { error("listener blew up") }
        listener { reached += "third" }

        SurfaceFocus.clear(UUID.randomUUID())

        assertEquals(listOf("first", "third"), reached)
    }

    @Test
    fun registeringTheSameListenerTwiceFiresItOnce() {
        var calls = 0
        val l = SurfaceFocus.FocusClearedListener { calls++ }
        SurfaceFocus.onFocusCleared(l)
        SurfaceFocus.onFocusCleared(l)
        registered += l

        SurfaceFocus.clear(UUID.randomUUID())

        assertEquals(1, calls, "a double registration would double every teardown")
    }

    @Test
    fun removedListenersStopFiring() {
        var calls = 0
        val l = SurfaceFocus.FocusClearedListener { calls++ }
        SurfaceFocus.onFocusCleared(l)
        SurfaceFocus.removeFocusClearedListener(l)

        SurfaceFocus.clear(UUID.randomUUID())

        assertEquals(0, calls)
    }

}
