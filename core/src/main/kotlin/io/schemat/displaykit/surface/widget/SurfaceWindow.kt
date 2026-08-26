package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.NineSliceLayout
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.layout.CrossAxis
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.PxPadding
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.SurfaceNode
import io.schemat.displaykit.surface.layout.WidgetNode
import io.schemat.displaykit.surface.titleBar

/** Visual and spacing policy for a conventional floating surface window. */
data class SurfaceWindowStyle(
    val frameId: SpriteId = SpriteId("gui", "tooltip/background"),
    val padding: Int = 10,
    val columnGap: Int = 4,
    val bodyGap: Int = 10,
    val titleMinHeight: Int = 24,
    val backdrop: DkColor = DkColor(190, 18, 19, 22),
    val backingBlock: BlockStateRef = BlockStateRef.BLACK_CONCRETE
)

/**
 * Reusable window composition for a [Surface].
 *
 * It owns every renderer-sensitive choice: exact nine-slice dimensions,
 * row-centred title height, frame/background stacking, padding, and title
 * stretching. An application supplies only its desired minimum size, title,
 * close action, and body children.
 */
class SurfaceWindow private constructor(
    val size: PxSize,
    private val frame: SpriteEntry?,
    val style: SurfaceWindowStyle
) {
    val titleHeight: Int = TextMetrics.centringHeight(style.titleMinHeight)

    /** Apply the window's material policy to its surface. */
    fun configure(surface: Surface) {
        surface.backdrop = style.backdrop
        surface.backingBlock = style.backingBlock
    }

    /**
     * Attach the complete window tree under [root]. [buildBody] receives a
     * growing horizontal body whose gaps and available size are already
     * correct.
     */
    fun build(
        root: SurfaceNode,
        title: String,
        onClose: () -> Unit,
        buildBody: (FlexNode) -> Unit
    ) {
        val frameNode = WidgetNode("window-frame", size) { painter, rect ->
            frame?.let { painter.frame(it, rect) }
        }
        root.addChild(frameNode)

        val column = FlexNode(
            "window-content",
            FlexDirection.COLUMN,
            crossAxis = CrossAxis.STRETCH,
            gap = style.columnGap
        )
        column.padding = PxPadding.all(style.padding)

        val innerWidth = (size.w - style.padding * 2).coerceAtLeast(0)
        val titleNode = WidgetNode(
            "window-title",
            PxSize(innerWidth, titleHeight)
        ) { painter, rect ->
            painter.titleBar(rect, title, onClose)
        }
        column.addChild(titleNode)

        val body = FlexNode("window-body", FlexDirection.ROW, gap = style.bodyGap)
        body.flexGrow = 1
        buildBody(body)
        column.addChild(body)
        root.addChild(column)
    }

    companion object {
        /** Smallest exactly tiled window at least [minWidth] x [minHeight]. */
        fun vanilla(
            minWidth: Int,
            minHeight: Int,
            style: SurfaceWindowStyle = SurfaceWindowStyle()
        ): SurfaceWindow {
            val frame = SpriteIndex.bundled.get(style.frameId)
            val measured = frame?.let {
                NineSliceLayout.exactSizeFor(it, minWidth, minHeight)
            } ?: (minWidth to minHeight)
            return SurfaceWindow(PxSize(measured.first, measured.second), frame, style)
        }
    }
}
