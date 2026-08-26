package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.surface.PointerButton
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimelineViewTest {
    private fun model(tick: Int = 25) = TimelineModel(
        durationTicks = 100,
        currentTick = tick,
        tracks = listOf(TimelineTrack("camera", "Camera", listOf(
            TimelineKeyframe("start", 0f), TimelineKeyframe("middle", 0.5f), TimelineKeyframe("end", 1f),
        ))),
    )

    @Test
    fun `timeline paints and maps clicks to semantic time and keyframes`() {
        var current = model()
        var seek = -1
        var selected: Pair<String, String>? = null
        val view = TimelineView("timeline", { current }, { seek = it }, { track, keyframe ->
            selected = track to keyframe
        })
        val surface = Surface(430, view.node.intrinsic.h, Vec3d.ZERO, 4f).also {
            it.renderMode = RenderMode.COMPOSITED
            it.layout { root -> root.addChild(view.node) }
            it.paintTree()
        }

        assertTrue(surface.canvasItemCount() > 0)
        surface.dispatch(SurfaceEvent.Click(267, 39, PointerButton.LEFT), view.node)
        assertEquals("camera" to "middle", selected)

        surface.dispatch(SurfaceEvent.Click(185, 39, PointerButton.LEFT), view.node)
        assertTrue(seek in 24..26)

        current = model(80)
        surface.paintTree()
        assertEquals(430, view.node.rect().w)
    }

    @Test
    fun `timeline rejects invalid semantic models`() {
        assertFailsWith<IllegalArgumentException> { TimelineKeyframe("bad", 1.1f) }
        assertFailsWith<IllegalArgumentException> { TimelineModel(0, 0, emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            TimelineTrack("dup", "Dup", listOf(TimelineKeyframe("x", 0f), TimelineKeyframe("x", 1f)))
        }
    }

    @Test
    fun `timeline scrolls a bounded track viewport`() {
        val tracks = (0 until 12).map { index ->
            TimelineTrack("track-$index", "Track $index", listOf(TimelineKeyframe("key-$index", 0.5f)))
        }
        val current = TimelineModel(100, 25, tracks)
        var selected: Pair<String, String>? = null
        var offset = -1
        val view = TimelineView(
            "timeline",
            { current },
            {},
            { track, keyframe -> selected = track to keyframe },
            onTrackOffsetChanged = { offset = it },
            style = TimelineViewStyle(maxVisibleTracks = 3),
        )
        val surface = Surface(430, view.node.intrinsic.h, Vec3d.ZERO, 4f).also {
            it.renderMode = RenderMode.COMPOSITED
            it.layout { root -> root.addChild(view.node) }
            it.paintTree()
        }

        assertEquals(124, view.node.intrinsic.h)
        assertTrue(surface.dispatch(SurfaceEvent.Scroll(200, 40, 2), view.node) != null)
        assertEquals(2, offset)
        surface.dispatch(SurfaceEvent.Click(267, 39, PointerButton.LEFT), view.node)
        assertEquals("track-2" to "key-2", selected)

        view.scrollToTrack(99)
        assertEquals(9, offset)
    }
}
