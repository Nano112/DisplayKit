package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.surface.layout.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurfaceHostTickTest {

    // A host needs a platform; use the existing test double pattern from
    // SurfaceTest. If none exists, create FakePlatform in this file with a
    // packetSender that records calls and does nothing else.

    @Test
    fun clickOnAGrabbableNodeStartsAGrabAndTheNextClickReleasesIt() {
        val s = Surface(100, 100, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 1f)
        val moves = mutableListOf<Pair<Int, Int>>()
        s.layout { root ->
            val thumb = WidgetNode("thumb", PxSize(10, 10))
            thumb.onGrabMove = { x, y -> moves += x to y }
            thumb.onEvent = { EventResult.PASS }
            root.addChild(thumb)
        }
        val thumb = s.nodeAt(5, 5)!!
        val player = java.util.UUID.randomUUID()

        assertTrue(SurfaceFocus.grab(player, thumb))
        assertEquals("thumb", SurfaceFocus.grabbed(player)?.id)
        SurfaceFocus.grabbed(player)?.onGrabMove?.invoke(7, 8)
        assertEquals(listOf(7 to 8), moves)
        SurfaceFocus.release(player)
        assertEquals(null, SurfaceFocus.grabbed(player))
        SurfaceFocus.clear(player)
    }

    @Test
    fun scrollBubblesFromACellToItsPane() {
        val s = Surface(100, 100, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 1f)
        val pane = ScrollNode("pane").also { it.stepPx = 10 }
        s.layout { root ->
            repeat(20) { pane.addChild(WidgetNode("row$it", PxSize(100, 10))) }
            pane.onEvent = { e ->
                if (e is SurfaceEvent.Scroll && pane.scrollBy(e.delta)) EventResult.CONSUMED
                else EventResult.PASS
            }
            root.addChild(pane)
        }
        val consumed = s.dispatch(SurfaceEvent.Scroll(5, 5, 2))
        assertEquals("pane", consumed?.id)
        assertEquals(20, pane.scrollPx)
    }

    @Test
    fun scrollAtTheEndOfTheRangeIsNotConsumedSoItCanBubbleFurther() {
        val s = Surface(100, 100, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 1f)
        val pane = ScrollNode("pane").also { it.stepPx = 10 }
        s.layout { root ->
            repeat(2) { pane.addChild(WidgetNode("row$it", PxSize(100, 10))) }
            pane.onEvent = { e ->
                if (e is SurfaceEvent.Scroll && pane.scrollBy(e.delta)) EventResult.CONSUMED
                else EventResult.PASS
            }
            root.addChild(pane)
        }
        assertFalse(pane.scrollBy(1), "content fits; nothing to scroll")
        assertEquals(null, s.dispatch(SurfaceEvent.Scroll(5, 5, 1)))
    }
}
