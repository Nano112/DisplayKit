package io.schemat.displaykit.surface

import io.schemat.displaykit.surface.layout.*
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class N(id: String) : BaseSurfaceNode(id) {
    override fun measureSelf(c: PxConstraints): PxSize = c.constrain(PxSize(10, 10))
}

/** Arms ONLY chat capture -- used to prove the two markers don't cross-arm. */
private class TextOnly(id: String) : BaseSurfaceNode(id), SurfaceNodeMarker.TextCapturing {
    override fun measureSelf(c: PxConstraints): PxSize = c.constrain(PxSize(10, 10))
}

class SurfaceFocusTest {

    private val player = UUID.randomUUID()

    @AfterTest fun cleanup() = SurfaceFocus.clear(player)

    @Test
    fun hoverTracksTheNodeUnderThePointer() {
        val a = N("a")
        assertTrue(SurfaceFocus.pointerAt(player, a), "entering a new node is a change")
        assertEquals("a", SurfaceFocus.state(player).hoveredId)
        assertFalse(SurfaceFocus.pointerAt(player, a), "same node is not a change")
    }

    @Test
    fun losingThePointerClearsHoverAndDisarmsScroll() {
        val pane = ScrollNode("pane")
        val cell = N("cell"); pane.addChild(cell)
        SurfaceFocus.pointerAt(player, cell)
        assertTrue(SurfaceFocus.isScrollArmed(player))
        SurfaceFocus.pointerLost(player)
        assertNull(SurfaceFocus.state(player).hoveredId)
        assertFalse(SurfaceFocus.isScrollArmed(player))
    }

    @Test
    fun scrollArmsOnlyInsideAScrollableAncestry() {
        val plain = N("plain")
        SurfaceFocus.pointerAt(player, plain)
        assertFalse(SurfaceFocus.isScrollArmed(player), "no scrollable ancestor")

        val pane = ScrollNode("pane")
        val deep = N("deep")
        val mid = N("mid")
        pane.addChild(mid); mid.addChild(deep)
        SurfaceFocus.pointerAt(player, deep)
        assertTrue(SurfaceFocus.isScrollArmed(player), "ancestor chain reaches a ScrollNode")
    }

    @Test
    fun textArmsOnlyInsideATextCapturingAncestry() {
        val plain = N("plain")
        SurfaceFocus.pointerAt(player, plain)
        assertFalse(SurfaceFocus.isTextArmed(player), "no TextCapturing ancestor")

        val capturing = TextOnly("terminal")
        val deep = N("deep")
        capturing.addChild(deep)
        SurfaceFocus.pointerAt(player, deep)
        assertTrue(SurfaceFocus.isTextArmed(player), "ancestor chain reaches a TextCapturing node")
    }

    @Test
    fun textAndScrollArmingAreIndependent() {
        // Both markers now share one ancestor walk (hasMarkedAncestor); this
        // guards against that sharing accidentally coupling the two.
        val scrollOnly = ScrollNode("scroll-only")
        val insideScroll = N("inside-scroll")
        scrollOnly.addChild(insideScroll)
        SurfaceFocus.pointerAt(player, insideScroll)
        assertTrue(SurfaceFocus.isScrollArmed(player), "sits under a Scrollable")
        assertFalse(SurfaceFocus.isTextArmed(player), "Scrollable alone must not arm text capture")

        val textOnly = TextOnly("text-only")
        val insideText = N("inside-text")
        textOnly.addChild(insideText)
        SurfaceFocus.pointerAt(player, insideText)
        assertTrue(SurfaceFocus.isTextArmed(player), "sits under a TextCapturing node")
        assertFalse(SurfaceFocus.isScrollArmed(player), "TextCapturing alone must not arm scroll")
    }

    @Test
    fun grabRequiresAGrabHandlerAndSurvivesUntilReleased() {
        val plain = N("plain")
        assertFalse(SurfaceFocus.grab(player, plain), "no onGrabMove means not grabbable")

        val thumb = N("thumb").also { it.onGrabMove = { _, _ -> } }
        assertTrue(SurfaceFocus.grab(player, thumb))
        assertEquals("thumb", SurfaceFocus.grabbed(player)?.id)
        SurfaceFocus.release(player)
        assertNull(SurfaceFocus.grabbed(player))
    }

    @Test
    fun losingThePointerDoesNotDropAnActiveGrab() {
        // Scrubbing a thumb can briefly leave the surface; dropping the grab
        // there would make dragging feel broken. Only release and close do.
        val thumb = N("thumb").also { it.onGrabMove = { _, _ -> } }
        SurfaceFocus.grab(player, thumb)
        SurfaceFocus.pointerLost(player)
        assertEquals("thumb", SurfaceFocus.grabbed(player)?.id)
    }

    @Test
    fun clearDropsEverythingForThatPlayer() {
        val thumb = N("thumb").also { it.onGrabMove = { _, _ -> } }
        SurfaceFocus.pointerAt(player, thumb)
        SurfaceFocus.grab(player, thumb)
        SurfaceFocus.clear(player)
        assertNull(SurfaceFocus.grabbed(player))
        assertNull(SurfaceFocus.state(player).hoveredId)
    }
}
