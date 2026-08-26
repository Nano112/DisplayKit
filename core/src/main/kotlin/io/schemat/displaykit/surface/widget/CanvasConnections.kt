package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.SurfacePainter
import io.schemat.displaykit.surface.layout.PxOffset

/** A directed relationship between two virtual-canvas points. */
data class CanvasConnection(val from: PxOffset, val to: PxOffset)

/** The four native sprites used to paint a directional graph edge. */
data class CanvasDirectedEdgeSprites(
    val left: SpriteEntry,
    val up: SpriteEntry,
    val right: SpriteEntry,
    val down: SpriteEntry
) {
    val entries: List<SpriteEntry> get() = listOf(left, up, right, down)

    companion object {
        fun bundled(): CanvasDirectedEdgeSprites? {
            val index = SpriteIndex.bundled
            return CanvasDirectedEdgeSprites(
                left = index.get(SpriteId("gui", "spectator/scroll_left")) ?: return null,
                up = index.get(SpriteId("gui", "statistics/sort_up")) ?: return null,
                right = index.get(SpriteId("gui", "spectator/scroll_right")) ?: return null,
                down = index.get(SpriteId("gui", "statistics/sort_down")) ?: return null
            )
        }
    }
}

/** Material and physical dimensions for a block-display canvas rail. */
data class CanvasEdgeRail(
    val block: BlockStateRef,
    val arrowTint: DkColor,
    val widthPx: Int = 3,
    val depth: Float = 0.03125f
) {
    init {
        require(widthPx > 0)
        require(depth > 0f)
    }
}

/**
 * Draw a clipped, orthogonal connector inside [viewport].
 *
 * The path bends halfway along X, giving trees and routes a stable visual
 * grammar without making applications repeat line clipping and pan offsets.
 */
fun SurfacePainter.canvasConnection(
    viewport: Rect,
    from: PxOffset,
    to: PxOffset,
    color: DkColor,
    thickness: Int = 2
) {
    require(thickness > 0)
    val middleX = (from.x + to.x) / 2
    clippedAxisSegment(viewport, from, PxOffset(middleX, from.y), color, thickness)
    clippedAxisSegment(viewport, PxOffset(middleX, from.y), PxOffset(middleX, to.y), color, thickness)
    clippedAxisSegment(viewport, PxOffset(middleX, to.y), to, color, thickness)
}

/**
 * Paint a directed, orthogonal graph edge with one native arrow sprite.
 *
 * Each straight rail run is a stretched block display. Runs stop at the edge
 * of the bend's block instead of overlapping it, and the final run stops
 * before the arrow. That leaves no coplanar faces competing at joints or at
 * the sprite arrowhead.
 */
fun SurfacePainter.canvasDirectedEdge(
    viewport: Rect,
    from: PxOffset,
    to: PxOffset,
    rail: CanvasEdgeRail,
    sprites: CanvasDirectedEdgeSprites,
    bendOffsetPx: Int = 0
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    if (dx == 0 && dy == 0) return

    if (dx != 0) {
        val sprite = if (dx > 0) sprites.right else sprites.left
        val arrowX = if (dx > 0) to.x - sprite.width else to.x
        val arrowY = to.y - sprite.height / 2
        val railEndX = if (dx > 0) arrowX - 2 else arrowX + sprite.width + 2
        val minimumBend = minOf(from.x, railEndX) + rail.widthPx / 2
        val maximumBend = maxOf(from.x, railEndX) - rail.widthPx / 2
        val preferredBend = (from.x + railEndX) / 2 + bendOffsetPx
        val bendX = if (minimumBend <= maximumBend) {
            preferredBend.coerceIn(minimumBend, maximumBend)
        } else {
            (from.x + railEndX) / 2
        }
        if (dy == 0) {
            horizontalBlockRail(viewport, from.x, railEndX, from.y, rail)
        } else {
            val bendLeft = bendX - rail.widthPx / 2
            val bendRight = bendLeft + rail.widthPx
            horizontalBlockRail(
                viewport,
                from.x,
                if (from.x <= bendX) bendLeft else bendRight,
                from.y,
                rail
            )
            verticalBlockRail(viewport, bendX, from.y, to.y, rail)
            horizontalBlockRail(
                viewport,
                if (railEndX >= bendX) bendRight else bendLeft,
                railEndX,
                to.y,
                rail
            )
        }
        if (arrowX >= viewport.x && arrowX + sprite.width <= viewport.right &&
            arrowY >= viewport.y && arrowY + sprite.height <= viewport.bottom
        ) {
            faceIcon(
                sprite,
                arrowX,
                arrowY,
                tint = rail.arrowTint,
                depthOffset = rail.depth + EDGE_FACE_BIAS
            )
        }
    } else {
        val sprite = if (dy > 0) sprites.down else sprites.up
        val arrowX = to.x - sprite.width / 2
        val arrowY = if (dy > 0) to.y - sprite.height else to.y
        val railEndY = if (dy > 0) arrowY - 2 else arrowY + sprite.height + 2
        verticalBlockRail(viewport, from.x, from.y, railEndY, rail)
        if (arrowX >= viewport.x && arrowX + sprite.width <= viewport.right &&
            arrowY >= viewport.y && arrowY + sprite.height <= viewport.bottom
        ) {
            faceIcon(
                sprite,
                arrowX,
                arrowY,
                tint = rail.arrowTint,
                depthOffset = rail.depth + EDGE_FACE_BIAS
            )
        }
    }
}

private fun SurfacePainter.horizontalBlockRail(
    viewport: Rect,
    fromX: Int,
    toX: Int,
    centerY: Int,
    rail: CanvasEdgeRail
) {
    val rect = Rect(
        minOf(fromX, toX),
        centerY - rail.widthPx / 2,
        kotlin.math.abs(toX - fromX),
        rail.widthPx
    )
    rect.intersection(viewport)?.takeIf { it.w > 0 && it.h > 0 }?.let {
        blockExtrusion(rail.block, it, rail.depth)
    }
}

private fun SurfacePainter.verticalBlockRail(
    viewport: Rect,
    centerX: Int,
    fromY: Int,
    toY: Int,
    rail: CanvasEdgeRail
) {
    val top = minOf(fromY, toY) - rail.widthPx / 2
    val rect = Rect(
        centerX - rail.widthPx / 2,
        top,
        rail.widthPx,
        kotlin.math.abs(toY - fromY) + rail.widthPx
    )
    rect.intersection(viewport)?.takeIf { it.w > 0 && it.h > 0 }?.let {
        blockExtrusion(rail.block, it, rail.depth)
    }
}

private const val EDGE_FACE_BIAS = 0.0001f

/** Paint a content-locked dot grid that makes canvas motion legible. */
fun SurfacePainter.canvasDotGrid(
    viewport: Rect,
    pan: PxOffset,
    color: DkColor,
    spacing: Int = 32
) {
    require(spacing >= TextMetrics.FONT_LINE_HEIGHT_PX)
    var x = viewport.x + Math.floorMod(-pan.x, spacing)
    while (x < viewport.right) {
        var y = viewport.y + Math.floorMod(-pan.y, spacing)
        while (y < viewport.bottom) {
            faceLabel(".", x, y, color)
            y += spacing
        }
        x += spacing
    }
}


private fun SurfacePainter.clippedAxisSegment(
    viewport: Rect,
    from: PxOffset,
    to: PxOffset,
    color: DkColor,
    thickness: Int
) {
    if (from.y == to.y) {
        val left = maxOf(minOf(from.x, to.x), viewport.x)
        val right = minOf(maxOf(from.x, to.x), viewport.right)
        val glyph = "."
        val advance = TextMetrics.textWidthPx(glyph).coerceAtLeast(1)
        val count = (right - left) / advance
        if (count > 0 && from.y in viewport.y until viewport.bottom) {
            faceLabel(glyph.repeat(count), left, from.y - TextMetrics.FONT_LINE_HEIGHT_PX / 2, color)
        }
    } else {
        val top = maxOf(minOf(from.y, to.y), viewport.y)
        val bottom = minOf(maxOf(from.y, to.y), viewport.bottom)
        if (from.x in viewport.x until viewport.right) {
            var y = top
            while (y + TextMetrics.FONT_LINE_HEIGHT_PX <= bottom) {
                faceLabel(".", from.x - thickness / 2, y, color)
                y += TextMetrics.FONT_LINE_HEIGHT_PX
            }
        }
    }
}
