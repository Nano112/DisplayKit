package io.schemat.displaykit.surface.layout

import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.SurfaceNodeMarker
import io.schemat.displaykit.surface.SurfacePainter
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Where a virtual canvas should begin when it is first measured. */
enum class CanvasInitialPosition { TOP_LEFT, CENTER_LEFT, CENTER }

/** A fixed viewport anchor for controls that must not move with canvas content. */
enum class CanvasOverlayAnchor {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
}

private data class CanvasOverlayPlacement(
    val anchor: CanvasOverlayAnchor,
    val offset: PxOffset
)

/**
 * A two-dimensional pannable viewport over a larger pixel canvas.
 *
 * Children are declared in content coordinates with [addAt]. Layout owns the
 * content-to-viewport transform, hit testing owns the same clipping boundary,
 * and painter traversal asks [visibleChildren] which objects are safe to draw.
 * This keeps window code free of camera offsets and duplicate clipping math.
 *
 * Canvas children are whole-object clipped because Minecraft text canvases do
 * not have a general scissor. Backgrounds and orthogonal connector underlays
 * can still fill the viewport exactly through [renderBackground] and
 * [renderUnderlay].
 */
class VirtualCanvasNode(
    id: String,
    val contentSize: PxSize,
    initialPosition: CanvasInitialPosition = CanvasInitialPosition.CENTER,
    /** Optional content-viewport aspect ratio, fitted and centred inside this node. */
    val viewportAspectRatio: Float? = null,
    var wheelStep: Int = 24,
    private val onViewportChanged: () -> Unit = {},
    var renderBackground: (SurfacePainter, Rect) -> Unit = { _, _ -> },
    var renderUnderlay: (SurfacePainter, Rect, PxOffset) -> Unit = { _, _, _ -> }
) : BaseSurfaceNode(id), SurfacePaintNode, ChildViewport, SurfaceNodeMarker.Scrollable {

    private val positions = IdentityHashMap<SurfaceNode, PxOffset>()
    private val overlays = IdentityHashMap<SurfaceNode, CanvasOverlayPlacement>()
    private var viewportW = 0
    private var viewportH = 0
    private var viewportOffsetX = 0
    private var viewportOffsetY = 0
    private var initialised = false
    private var grabPoint: PxOffset? = null
    private val initialPosition = initialPosition

    var panX: Int = 0
        private set
    var panY: Int = 0
        private set

    init {
        require(contentSize.w > 0 && contentSize.h > 0) { "virtual canvas content must be positive" }
        require(viewportAspectRatio == null || viewportAspectRatio > 0f) {
            "viewport aspect ratio must be positive"
        }
        require(wheelStep > 0) { "wheelStep must be positive" }
        flexGrow = 1

        onEvent = { event ->
            if (event is SurfaceEvent.Scroll && panBy(0, event.delta * wheelStep)) {
                EventResult.CONSUMED
            } else EventResult.PASS
        }
        onGrabStart = { grabPoint = null }
        onGrabMove = { x, y ->
            val previous = grabPoint
            grabPoint = PxOffset(x, y)
            if (previous != null) panBy(previous.x - x, previous.y - y)
        }
        onGrabEnd = { grabPoint = null }
    }

    /** Attach [child] at a stable content-space origin. */
    fun addAt(child: SurfaceNode, x: Int, y: Int) {
        require(x >= 0 && y >= 0) { "canvas child positions must be non-negative" }
        positions[child] = PxOffset(x, y)
        addChild(child)
    }

    /**
     * Attach [child] to the viewport rather than the virtual content plane.
     *
     * Overlay children remain fixed while the canvas pans and are painted and
     * hit-tested above content children. [offset] is applied after anchoring,
     * so negative values naturally inset right and bottom anchored controls.
     */
    fun addOverlay(
        child: SurfaceNode,
        anchor: CanvasOverlayAnchor,
        offset: PxOffset = PxOffset.Zero
    ) {
        overlays[child] = CanvasOverlayPlacement(anchor, offset)
        addChild(child)
    }

    override fun clearChildren() {
        super.clearChildren()
        positions.clear()
        overlays.clear()
    }

    override fun measureSelf(c: PxConstraints): PxSize {
        val inner = c.deflate(padding)
        for (child in _children) {
            child.measure(PxConstraints.upTo(contentSize.w, contentSize.h))
        }
        val measured = c.constrain(PxSize(inner.maxW + padding.horizontal, inner.maxH + padding.vertical))
        val availableW = (measured.w - padding.horizontal).coerceAtLeast(0)
        val availableH = (measured.h - padding.vertical).coerceAtLeast(0)
        viewportW = availableW
        viewportH = availableH
        viewportAspectRatio?.let { ratio ->
            if (availableH > 0 && availableW.toFloat() / availableH > ratio) {
                viewportW = (availableH * ratio).roundToInt().coerceAtMost(availableW)
            } else if (availableW > 0) {
                viewportH = (availableW / ratio).roundToInt().coerceAtMost(availableH)
            }
        }
        viewportOffsetX = padding.left + (availableW - viewportW) / 2
        viewportOffsetY = padding.top + (availableH - viewportH) / 2

        if (!initialised) {
            panX = if (initialPosition == CanvasInitialPosition.CENTER) maxPanX() / 2 else 0
            panY = if (initialPosition == CanvasInitialPosition.TOP_LEFT) 0 else maxPanY() / 2
            initialised = true
        } else {
            panX = panX.coerceIn(0, maxPanX())
            panY = panY.coerceIn(0, maxPanY())
        }
        return measured
    }

    override fun place(offset: PxOffset) {
        super.place(offset)
        for (child in _children) {
            val overlay = overlays[child]
            if (overlay != null) {
                child.place(overlayOffset(child, overlay))
            } else {
                val content = positions[child] ?: PxOffset.Zero
                child.place(
                    PxOffset(
                        viewportOffsetX + content.x - panX,
                        viewportOffsetY + content.y - panY
                    )
                )
            }
        }
    }

    fun maxPanX(): Int = (contentSize.w - viewportW).coerceAtLeast(0)
    fun maxPanY(): Int = (contentSize.h - viewportH).coerceAtLeast(0)

    fun panBy(dx: Int, dy: Int): Boolean = panTo(panX + dx, panY + dy)

    fun panTo(x: Int, y: Int): Boolean {
        val nextX = x.coerceIn(0, maxPanX())
        val nextY = y.coerceIn(0, maxPanY())
        if (nextX == panX && nextY == panY) return false
        panX = nextX
        panY = nextY
        place(layoutResult?.offset ?: PxOffset.Zero)
        onViewportChanged()
        return true
    }

    /** Convert a point from virtual content coordinates into canvas pixels. */
    fun toViewportPoint(x: Int, y: Int): PxOffset {
        val viewport = viewportRect()
        return PxOffset(viewport.x + x - panX, viewport.y + y - panY)
    }

    fun viewportRect(): Rect {
        val r = rect()
        return Rect(
            r.x + viewportOffsetX,
            r.y + viewportOffsetY,
            viewportW,
            viewportH
        )
    }

    override fun paint(painter: SurfacePainter) {
        val viewport = viewportRect()
        renderBackground(painter, viewport)
        renderUnderlay(painter, viewport, PxOffset(panX, panY))
    }

    override fun visibleChildren(): List<SurfaceNode> {
        val viewport = viewportRect()
        val content = _children.filter { child ->
            child !in overlays && run {
            val r = child.rect()
            r.x >= viewport.x && r.y >= viewport.y &&
                r.right <= viewport.right && r.bottom <= viewport.bottom
            }
        }.sortedWith(compareBy({ it.rect().y }, { it.rect().x }))
        // Fixed controls intentionally paint after content and therefore win
        // the reversed hit-test walk below.
        return content + _children.filter { it in overlays }
    }

    override fun hitTest(x: Int, y: Int): SurfaceNode? {
        if (!viewportRect().contains(x, y)) return null
        for (child in visibleChildren().asReversed()) {
            child.hitTest(x, y)?.let { return it }
        }
        return this
    }

    private fun overlayOffset(
        child: SurfaceNode,
        placement: CanvasOverlayPlacement
    ): PxOffset {
        val size = child.layoutResult?.size ?: PxSize.Zero
        val horizontal = when (placement.anchor) {
            CanvasOverlayAnchor.TOP_LEFT,
            CanvasOverlayAnchor.CENTER_LEFT,
            CanvasOverlayAnchor.BOTTOM_LEFT -> 0

            CanvasOverlayAnchor.TOP_CENTER,
            CanvasOverlayAnchor.CENTER,
            CanvasOverlayAnchor.BOTTOM_CENTER -> (viewportW - size.w) / 2

            CanvasOverlayAnchor.TOP_RIGHT,
            CanvasOverlayAnchor.CENTER_RIGHT,
            CanvasOverlayAnchor.BOTTOM_RIGHT -> viewportW - size.w
        }
        val vertical = when (placement.anchor) {
            CanvasOverlayAnchor.TOP_LEFT,
            CanvasOverlayAnchor.TOP_CENTER,
            CanvasOverlayAnchor.TOP_RIGHT -> 0

            CanvasOverlayAnchor.CENTER_LEFT,
            CanvasOverlayAnchor.CENTER,
            CanvasOverlayAnchor.CENTER_RIGHT -> (viewportH - size.h) / 2

            CanvasOverlayAnchor.BOTTOM_LEFT,
            CanvasOverlayAnchor.BOTTOM_CENTER,
            CanvasOverlayAnchor.BOTTOM_RIGHT -> viewportH - size.h
        }
        return PxOffset(
            viewportOffsetX + horizontal + placement.offset.x,
            viewportOffsetY + vertical + placement.offset.y
        )
    }
}
