package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.action.AsyncAction
import io.schemat.displaykit.action.ConfirmAction
import io.schemat.displaykit.action.OperationStatus
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.state.StateScope
import io.schemat.displaykit.surface.PointerButton
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceEvent
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeedbackViewsTest {
    @Test
    fun `status and empty states paint through retained nodes`() {
        val status = StatusBannerView("status", { StatusBannerModel("Saved", StatusTone.SUCCESS) })
        val empty = EmptyStateView("empty", { EmptyStateModel("Nothing here", "Try another filter") })
        val surface = Surface(240, 60, Vec3d.ZERO, 2f).also {
            it.renderMode = RenderMode.ENTITIES
            it.layout { root -> root.addChild(status.node); root.addChild(empty.node) }
            it.paintTree()
        }
        assertTrue(surface.paintedLabelYsForTest().isNotEmpty())
    }

    @Test
    fun `async button composes expiring confirmation and overlap rejection`() {
        val scope = StateScope()
        val action = AsyncAction<Int>(scope)
        val confirmation = ConfirmAction(scope, 20)
        val future = CompletableFuture<Int>()
        var starts = 0
        val view = AsyncActionButtonView(
            id = "save",
            label = { "Save" },
            action = action,
            task = { starts++; future },
            isHovered = { false },
            confirmation = confirmation,
        )
        val surface = Surface(view.node.intrinsic.w, view.node.intrinsic.h, Vec3d.ZERO, 2f).also {
            it.renderMode = RenderMode.ENTITIES
            it.layout { root -> root.addChild(view.node) }
        }

        surface.dispatch(SurfaceEvent.Click(1, 1, PointerButton.LEFT), view.node)
        assertTrue(confirmation.isArmed)
        assertEquals(0, starts)
        surface.dispatch(SurfaceEvent.Click(1, 1, PointerButton.LEFT), view.node)
        assertEquals(1, starts)
        assertIs<OperationStatus.Running>(action.status.value)
        surface.dispatch(SurfaceEvent.Click(1, 1, PointerButton.LEFT), view.node)
        assertEquals(1, starts)
        future.complete(7)
        assertEquals(7, assertIs<OperationStatus.Succeeded<Int>>(action.status.value).value)
    }

    @Test
    fun `async statuses map to semantic banner tones`() {
        assertEquals(StatusTone.INFO, OperationStatus.Running("Loading").toStatusBanner().tone)
        assertEquals(StatusTone.SUCCESS, OperationStatus.Succeeded(Unit).toStatusBanner().tone)
        assertEquals(StatusTone.ERROR, OperationStatus.Failed(IllegalStateException("bad")).toStatusBanner().tone)
    }
}
