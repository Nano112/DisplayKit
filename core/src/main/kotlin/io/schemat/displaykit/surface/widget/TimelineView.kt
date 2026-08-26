package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.PointerButton
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.SurfaceNodeMarker
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode
import kotlin.math.abs
import kotlin.math.roundToInt

/** One stable marker on a [TimelineTrack]. */
data class TimelineKeyframe(
    val id: String,
    val time: Float,
    val label: String? = null,
) {
    init {
        require(id.isNotBlank()) { "A timeline keyframe id cannot be blank" }
        require(time.isFinite() && time in 0f..1f) {
            "Timeline keyframe '$id' time must be normalized to 0..1"
        }
    }
}

/** One named row in a [TimelineModel]. */
data class TimelineTrack(
    val id: String,
    val label: String,
    val keyframes: List<TimelineKeyframe>,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "A timeline track id cannot be blank" }
        require(label.isNotBlank()) { "Timeline track '$id' label cannot be blank" }
        require(keyframes.map { it.id }.distinct().size == keyframes.size) {
            "Timeline track '$id' keyframe ids must be unique"
        }
    }
}

/** Renderer-neutral state shared by a world timeline and any future screen or HUD renderer. */
data class TimelineModel(
    val durationTicks: Int,
    val currentTick: Int,
    val tracks: List<TimelineTrack>,
    val selectedTrackId: String? = null,
    val selectedKeyframeId: String? = null,
    val playing: Boolean = false,
) {
    init {
        require(durationTicks > 0) { "Timeline duration must be positive" }
        require(currentTick in 0..durationTicks) { "Timeline current tick must be inside its duration" }
        require(tracks.map { it.id }.distinct().size == tracks.size) { "Timeline track ids must be unique" }
        require(selectedTrackId == null || tracks.any { it.id == selectedTrackId }) {
            "Selected timeline track '$selectedTrackId' does not exist"
        }
        require(selectedKeyframeId == null || tracks.any { track ->
            track.id == selectedTrackId && track.keyframes.any { it.id == selectedKeyframeId }
        }) { "Selected timeline keyframe '$selectedKeyframeId' does not exist on '$selectedTrackId'" }
    }
}

data class TimelineViewStyle(
    val width: Int = 430,
    val labelWidth: Int = 104,
    val rulerHeight: Int = 22,
    val rowHeight: Int = 34,
    val trackHeight: Int = 10,
    val markerSize: Int = 14,
    val maxVisibleTracks: Int = 8,
    val background: DkColor = DkColor(230, 25, 27, 32),
    val trackBlock: BlockStateRef = BlockStateRef.GRAY_CONCRETE,
    val disabledTrackBlock: BlockStateRef = BlockStateRef.BLACK_CONCRETE,
    val markerBlock: BlockStateRef = BlockStateRef("minecraft:light_gray_concrete"),
    val selectedMarkerBlock: BlockStateRef = BlockStateRef("minecraft:gold_block"),
    val playheadBlock: BlockStateRef = BlockStateRef("minecraft:red_concrete"),
) {
    init {
        require(width >= 160) { "Timeline width must be at least 160 pixels" }
        require(labelWidth in 40..width - 80) { "Timeline label width leaves no usable track" }
        require(rulerHeight >= TextMetrics.FONT_LINE_HEIGHT_PX) { "Timeline ruler must fit text" }
        require(rowHeight >= markerSize) { "Timeline rows must fit their markers" }
        require(trackHeight > 0 && markerSize > 0) { "Timeline geometry must be positive" }
        require(maxVisibleTracks > 0) { "Timeline must show at least one track" }
    }
}

/**
 * General-purpose, retained timeline primitive.
 *
 * The application supplies semantic IDs and normalized time. This primitive
 * owns row geometry, ruler alignment, marker hit testing, selection, and seek
 * conversion, so feature code never patches world coordinates or depth.
 */
class TimelineView(
    id: String,
    private val model: () -> TimelineModel,
    private val onSeek: (Int) -> Unit,
    private val onKeyframeSelected: (trackId: String, keyframeId: String) -> Unit,
    private val onTrackOffsetChanged: (Int) -> Unit = {},
    val style: TimelineViewStyle = TimelineViewStyle(),
) {
    private var trackOffset = 0

    val node: WidgetNode = TimelineNode(
        id,
        PxSize(style.width, heightFor(model().tracks.size)),
    ) { painter, rect ->
        val current = model()
        painter.fill(style.background, rect)
        paintRuler(painter, rect, current)
        val offset = currentOffset(current.tracks.size)
        current.tracks.drop(offset).take(visibleTrackCount(rect)).forEachIndexed { index, track ->
            paintTrack(painter, rect, current, track, index)
        }
        paintPlayhead(painter, rect, current)
    }.also { node ->
        node.onEvent = event@ { event ->
            if (event is SurfaceEvent.Scroll) {
                val current = model()
                val next = (currentOffset(current.tracks.size) + event.delta)
                    .coerceIn(0, maxTrackOffset(current.tracks.size))
                if (next == trackOffset) return@event EventResult.PASS
                trackOffset = next
                onTrackOffsetChanged(next)
                return@event EventResult.CONSUMED
            }
            if (event !is SurfaceEvent.Click || event.button != PointerButton.LEFT) {
                return@event EventResult.PASS
            }
            val current = model()
            if (!node.rect().contains(event.x, event.y)) return@event EventResult.PASS
            val row = ((event.y - node.rect().y - style.rulerHeight) / style.rowHeight)
            val track = current.tracks.getOrNull(currentOffset(current.tracks.size) + row)
            if (track != null) {
                val marker = nearestMarker(node.rect(), track, event.x)
                if (marker != null) {
                    onKeyframeSelected(track.id, marker.id)
                    return@event EventResult.CONSUMED
                }
            }
            if (event.x >= trackLeft(node.rect())) {
                onSeek(tickAt(node.rect(), current, event.x))
                EventResult.CONSUMED
            } else {
                EventResult.PASS
            }
        }
    }

    /** Update natural height after the caller changes the number of tracks. */
    fun refreshLayout() {
        trackOffset = currentOffset(model().tracks.size)
        node.intrinsic = PxSize(style.width, heightFor(model().tracks.size))
    }

    /** Scroll to a stable track index without exposing layout coordinates. */
    fun scrollToTrack(index: Int) {
        val next = index.coerceIn(0, maxTrackOffset(model().tracks.size))
        if (next != trackOffset) {
            trackOffset = next
            onTrackOffsetChanged(next)
        }
    }

    private fun heightFor(trackCount: Int): Int = style.rulerHeight +
        trackCount.coerceAtLeast(1).coerceAtMost(style.maxVisibleTracks) * style.rowHeight

    private fun maxTrackOffset(trackCount: Int): Int =
        (trackCount - style.maxVisibleTracks).coerceAtLeast(0)

    private fun currentOffset(trackCount: Int): Int =
        trackOffset.coerceIn(0, maxTrackOffset(trackCount))

    private fun paintRuler(
        painter: io.schemat.displaykit.surface.SurfacePainter,
        rect: Rect,
        model: TimelineModel,
    ) {
        val y = TextMetrics.rowAlignedY(rect.y + 4)
        painter.faceLabel("0t", trackLeft(rect), y)
        val end = "${model.durationTicks}t"
        painter.faceLabel(end, rect.right - TextMetrics.textWidthPx(end) - 4, y)
        val state = if (model.playing) "Playing" else "Scrub"
        painter.faceLabel(state, rect.x + 4, y, if (model.playing) DkColor.fromRGB(85, 255, 85) else null)
    }

    private fun paintTrack(
        painter: io.schemat.displaykit.surface.SurfacePainter,
        rect: Rect,
        model: TimelineModel,
        track: TimelineTrack,
        index: Int,
    ) {
        val rowTop = rect.y + style.rulerHeight + index * style.rowHeight
        val centreY = rowTop + style.rowHeight / 2
        painter.faceLabel(
            TextMetrics.ellipsize(track.label, style.labelWidth - 8),
            rect.x + 4,
            TextMetrics.rowAlignedY(centreY - TextMetrics.FONT_LINE_HEIGHT_PX / 2),
            if (track.enabled) null else DkColor.fromRGB(170, 170, 170),
        )
        val bar = Rect(trackLeft(rect), centreY - style.trackHeight / 2, trackWidth(rect), style.trackHeight)
        painter.blockBacking(if (track.enabled) style.trackBlock else style.disabledTrackBlock, bar, 0.025f)
        track.keyframes.forEach { keyframe ->
            val x = xAt(rect, keyframe.time)
            val selected = model.selectedTrackId == track.id && model.selectedKeyframeId == keyframe.id
            painter.identity("track:${track.id}:keyframe:${keyframe.id}") {
                blockExtrusion(
                    if (selected) style.selectedMarkerBlock else style.markerBlock,
                    Rect(x - style.markerSize / 2, centreY - style.markerSize / 2, style.markerSize, style.markerSize),
                    if (selected) 0.075f else 0.05f,
                )
            }
        }
    }

    private fun paintPlayhead(
        painter: io.schemat.displaykit.surface.SurfacePainter,
        rect: Rect,
        model: TimelineModel,
    ) {
        val x = xAt(rect, model.currentTick.toFloat() / model.durationTicks)
        painter.identity("playhead") {
            blockExtrusion(
                style.playheadBlock,
                Rect(x - 1, rect.y + style.rulerHeight - 2, 3, rect.h - style.rulerHeight + 2),
                0.035f,
            )
        }
    }

    private fun nearestMarker(rect: Rect, track: TimelineTrack, x: Int): TimelineKeyframe? =
        track.keyframes.minByOrNull { abs(xAt(rect, it.time) - x) }
            ?.takeIf { abs(xAt(rect, it.time) - x) <= style.markerSize }

    private fun trackLeft(rect: Rect): Int = rect.x + style.labelWidth
    private fun trackWidth(rect: Rect): Int = rect.w - style.labelWidth - 4
    private fun visibleTrackCount(rect: Rect): Int =
        ((rect.h - style.rulerHeight) / style.rowHeight).coerceAtLeast(0)
    private fun xAt(rect: Rect, normalized: Float): Int =
        trackLeft(rect) + (trackWidth(rect) * normalized.coerceIn(0f, 1f)).roundToInt()

    private fun tickAt(rect: Rect, model: TimelineModel, x: Int): Int {
        val normalized = ((x - trackLeft(rect)).toFloat() / trackWidth(rect)).coerceIn(0f, 1f)
        return (normalized * model.durationTicks).roundToInt()
    }
}

private class TimelineNode(
    id: String,
    intrinsic: PxSize,
    render: (io.schemat.displaykit.surface.SurfacePainter, Rect) -> Unit,
) : WidgetNode(id, intrinsic, render), SurfaceNodeMarker.Scrollable
