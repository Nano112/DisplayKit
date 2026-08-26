package io.schemat.displaykit.fabric.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceAnchor
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.SurfaceFocus
import io.schemat.displaykit.surface.SurfaceLifecyclePolicy
import io.schemat.displaykit.surface.SurfacePose
import io.schemat.displaykit.surface.WorldSurfaceSession
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode
import java.util.function.Supplier
import java.util.concurrent.atomic.AtomicLong

/** A compact, auto-facing, block-backed interactive handle in world space. */
class WorldHandlePresentation private constructor(
    private val owner: PlayerRef,
    private val label: String,
    private val center: () -> Vec3d?,
    private val base: BlockStateRef,
    private val hover: BlockStateRef,
    private val onClick: () -> Unit,
    private val onClosed: () -> Unit,
) : AutoCloseable {
    private val id = "world-handle-${nextId.incrementAndGet()}"
    private val surface = Surface(WIDTH, HEIGHT, Vec3d.ZERO, TARGET_WIDTH_BLOCKS).also {
        it.renderMode = RenderMode.AUTO
        it.backdrop = null
        it.backingBlock = null
        it.layout { root ->
            root.addChild(WidgetNode(id, PxSize(WIDTH, HEIGHT)) { painter, rect ->
                val hovered = SurfaceFocus.state(owner.uuid).hoveredId == id
                painter.blockBacking(if (hovered) hover else base, rect, BLOCK_DEPTH)
                painter.fill(if (hovered) HOVER_FACE else FACE, rect)
                val text = TextMetrics.ellipsize(label, rect.w - 4)
                painter.faceLabel(
                    text,
                    rect.x + (rect.w - TextMetrics.textWidthPx(text)) / 2,
                    TextMetrics.rowAlignedY(rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2),
                )
            }.also { node ->
                node.onEvent = { event ->
                    if (event is SurfaceEvent.Click) {
                        onClick()
                        EventResult.CONSUMED
                    } else EventResult.PASS
                }
            })
        }
    }
    private val presentation = FabricSurfacePresentation(
        owner = owner,
        surface = surface,
        anchor = SurfaceAnchor.dynamic { viewer ->
            center()?.let { at -> SurfacePose(at, Surface.yawFacing(at - viewer.eyePosition())) }
        },
        lifecycle = SurfaceLifecyclePolicy(maxDistance = 30.0, timeoutTicks = 72_000),
        diagnosticLabel = "world-handle",
        onClosed = { onClosed() },
    )

    val session: WorldSurfaceSession get() = presentation.session

    fun present(): WorldHandlePresentation {
        presentation.present()
        return this
    }

    override fun close() = presentation.close()

    companion object {
        private const val WIDTH = 96
        private const val HEIGHT = 24
        private const val TARGET_WIDTH_BLOCKS = 0.48f
        private const val BLOCK_DEPTH = 0.16f
        private val FACE = DkColor(225, 25, 28, 34)
        private val HOVER_FACE = DkColor(225, 45, 50, 58)
        private val nextId = AtomicLong()

        /** Java-friendly factory used by editor and visualization integrations. */
        @JvmStatic
        @JvmOverloads
        fun create(
            owner: PlayerRef,
            label: String,
            center: Supplier<Vec3d?>,
            base: BlockStateRef = BlockStateRef.CYAN_CONCRETE,
            hover: BlockStateRef = BlockStateRef.LIGHT_BLUE_CONCRETE,
            onClick: Runnable,
            onClosed: Runnable = Runnable {},
        ): WorldHandlePresentation = WorldHandlePresentation(
            owner, label, { center.get() }, base, hover,
            onClick = onClick::run,
            onClosed = onClosed::run,
        )
    }
}
