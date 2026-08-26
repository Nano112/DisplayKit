package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.hud.ProgressBarColor
import io.schemat.displaykit.hud.ProgressBarModel
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.RenderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProgressBarViewTest {
    @Test
    fun `surface progress renders from shared model`() {
        var model = ProgressBarModel("job", "Half", 0.5f, ProgressBarColor.GREEN)
        val view = ProgressBarView("progress", { model }, ProgressBarViewStyle(width = 100, height = 16))
        val surface = Surface(100, 20, Vec3d.ZERO, 1f)
        surface.renderMode = RenderMode.COMPOSITED
        surface.layout { it.addChild(view.node) }
        surface.paintTree()

        assertTrue(surface.canvasItemCount() > 0)
        assertEquals(100, view.node.rect().w)

        model = model.copy(progress = 1f, title = io.schemat.displaykit.render.TextComponent.of("Done"))
        surface.paintTree()
        assertEquals(100, view.node.rect().w)
    }

    @Test
    fun `text progress requires enough height`() {
        assertFailsWith<IllegalArgumentException> { ProgressBarViewStyle(height = 0) }
    }
}
