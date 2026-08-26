package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.layout.CanvasOverlayAnchor
import io.schemat.displaykit.surface.layout.PxOffset
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.VirtualCanvasNode
import io.schemat.displaykit.surface.layout.WidgetNode

enum class CanvasPanDirection { LEFT, UP, RIGHT, DOWN }

/** Visual and movement policy for fixed canvas navigation controls. */
data class CanvasPanControlsStyle(
    val size: Int = 32,
    val viewportInset: Int = 6,
    val step: Int = 96,
    val hoveredTint: DkColor = DkColor.fromRGB(255, 214, 92),
    val disabledTint: DkColor = DkColor.fromRGB(83, 89, 99),
    val sprites: CanvasPanControlSprites = CanvasPanControlSprites()
) {
    init {
        require(size >= 32) { "canvas pan controls must be at least 32px" }
        require(viewportInset >= 0)
        require(step > 0)
    }
}

/** Bundled sprite states used by the four navigation controls. */
data class CanvasPanControlSprites(
    val left: SpriteId = SpriteId("gui", "spectator/scroll_left"),
    val leftHighlighted: SpriteId = left,
    val up: SpriteId = SpriteId("gui", "server_list/move_up"),
    val upHighlighted: SpriteId = SpriteId("gui", "server_list/move_up_highlighted"),
    val right: SpriteId = SpriteId("gui", "spectator/scroll_right"),
    val rightHighlighted: SpriteId = right,
    val down: SpriteId = SpriteId("gui", "server_list/move_down"),
    val downHighlighted: SpriteId = SpriteId("gui", "server_list/move_down_highlighted")
)

private data class CanvasPanControlVisual(
    val normal: SpriteEntry,
    val highlighted: SpriteEntry
)

/**
 * Install four viewport-anchored pan buttons on this canvas.
 *
 * The controls are regular layout nodes: their placement, draw order and hit
 * priority come from [VirtualCanvasNode.addOverlay], while movement still goes
 * through the canvas' clamped [VirtualCanvasNode.panBy] transform.
 */
fun VirtualCanvasNode.addPanControls(
    idPrefix: String,
    isHovered: (String) -> Boolean,
    style: CanvasPanControlsStyle = CanvasPanControlsStyle()
): Map<CanvasPanDirection, WidgetNode> {
    val result = linkedMapOf<CanvasPanDirection, WidgetNode>()
    for (direction in CanvasPanDirection.entries) {
        val visual = style.sprites.resolve(direction) ?: continue
        val nodeId = "$idPrefix-${direction.name.lowercase()}"
        val node = WidgetNode(nodeId, PxSize(style.size, style.size)) { painter, rect ->
            val enabled = canPan(direction)
            val hovered = isHovered(nodeId) && enabled
            painter.elevate(2) {
                val sprite = if (hovered) visual.highlighted else visual.normal
                faceIcon(
                    sprite,
                    rect.centeredX(sprite.width),
                    rect.centeredY(sprite.height),
                    tint = when {
                        !enabled -> style.disabledTint
                        hovered && visual.highlighted == visual.normal -> style.hoveredTint
                        else -> null
                    }
                )
            }
        }
        node.onEvent = { event ->
            when (event) {
                is SurfaceEvent.Click -> {
                    pan(direction, style.step)
                    EventResult.CONSUMED
                }
                is SurfaceEvent.PointerEnter, is SurfaceEvent.PointerExit -> EventResult.CONSUMED
                else -> EventResult.PASS
            }
        }
        node.onPrepare = {
            SpriteGlyphs.warmAllPhases(visual.normal)
            SpriteGlyphs.warmAllPhases(visual.highlighted)
        }
        val placement = direction.placement(style.viewportInset)
        addOverlay(node, placement.first, placement.second)
        result[direction] = node
    }
    return result
}

private fun CanvasPanDirection.placement(inset: Int): Pair<CanvasOverlayAnchor, PxOffset> =
    when (this) {
        CanvasPanDirection.LEFT -> CanvasOverlayAnchor.CENTER_LEFT to PxOffset(inset, 0)
        CanvasPanDirection.UP -> CanvasOverlayAnchor.TOP_LEFT to PxOffset(inset, inset)
        CanvasPanDirection.RIGHT -> CanvasOverlayAnchor.CENTER_RIGHT to PxOffset(-inset, 0)
        CanvasPanDirection.DOWN -> CanvasOverlayAnchor.BOTTOM_LEFT to PxOffset(inset, -inset)
    }

private fun VirtualCanvasNode.canPan(direction: CanvasPanDirection): Boolean = when (direction) {
    CanvasPanDirection.LEFT -> panX > 0
    CanvasPanDirection.UP -> panY > 0
    CanvasPanDirection.RIGHT -> panX < maxPanX()
    CanvasPanDirection.DOWN -> panY < maxPanY()
}

private fun VirtualCanvasNode.pan(direction: CanvasPanDirection, step: Int): Boolean = when (direction) {
    CanvasPanDirection.LEFT -> panBy(-step, 0)
    CanvasPanDirection.UP -> panBy(0, -step)
    CanvasPanDirection.RIGHT -> panBy(step, 0)
    CanvasPanDirection.DOWN -> panBy(0, step)
}

private fun CanvasPanControlSprites.resolve(
    direction: CanvasPanDirection
): CanvasPanControlVisual? {
    val (normalId, highlightedId) = when (direction) {
        CanvasPanDirection.LEFT -> left to leftHighlighted
        CanvasPanDirection.UP -> up to upHighlighted
        CanvasPanDirection.RIGHT -> right to rightHighlighted
        CanvasPanDirection.DOWN -> down to downHighlighted
    }
    val normal = SpriteIndex.bundled.get(normalId) ?: return null
    val highlighted = SpriteIndex.bundled.get(highlightedId) ?: return null
    return CanvasPanControlVisual(normal, highlighted)
}
