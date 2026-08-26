package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.property.BooleanPropertyField
import io.schemat.displaykit.property.IntPropertyField
import io.schemat.displaykit.property.PropertySheetModel
import io.schemat.displaykit.surface.PointerButton
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.layout.SurfaceNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PropertySheetViewTest {
    @Test
    fun controlsUseStableCompositionAndOwnBounds() {
        var signal = 1
        var enabled = false
        val signalField = IntPropertyField(
            "signal", "Signal", 0, 2,
            get = { signal }, set = { signal = it }
        )
        val enabledField = BooleanPropertyField(
            "enabled", "Enabled", get = { enabled }, set = { enabled = it }
        )
        var changes = 0
        val view = PropertySheetView(
            "properties",
            PropertySheetModel(listOf(signalField, enabledField)),
            isHovered = { false },
            onChanged = { changes++ }
        )
        val surface = Surface(220, 160, Vec3d.ZERO, 2f)
        surface.layout { it.addChild(view.node) }

        click(surface, "properties-signal-next")
        click(surface, "properties-signal-next")
        click(surface, "properties-enabled-next")

        assertEquals(2, signal)
        assertEquals(true, enabled)
        assertEquals(2, changes, "a boundary press must not publish a false change")
        assertNotNull(surface.root?.find("properties-field-signal"))
        assertNotNull(surface.root?.find("properties-signal-value"))
    }

    private fun click(surface: Surface, id: String) {
        val node = assertNotNull(surface.root?.find(id))
        surface.dispatch(
            SurfaceEvent.Click(node.rect().x + 1, node.rect().y + 1, PointerButton.RIGHT),
            node
        )
    }

    private fun SurfaceNode.find(id: String): SurfaceNode? {
        if (this.id == id) return this
        return children.firstNotNullOfOrNull { it.find(id) }
    }
}
