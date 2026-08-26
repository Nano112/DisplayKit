package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteFit
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.Rect
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.layout.CanvasInitialPosition
import io.schemat.displaykit.surface.layout.PxOffset
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.VirtualCanvasNode
import io.schemat.displaykit.surface.layout.WidgetNode

data class MapMarker(
    val id: String,
    val label: String,
    val x: Int,
    val y: Int,
    val icon: SpriteId,
    val color: DkColor = DkColor.fromRGB(216, 184, 126)
)

data class MapRoute(val from: String, val to: String)

data class CartographyMapStyle(
    val markerBadge: SpriteId = SpriteId("gui", "container/inventory/effect_background"),
    val badgeFallbackSize: PxSize = PxSize(32, 32),
    val labelGap: Int = 3,
    val labelPaddingRight: Int = 2,
    val parchment: DkColor = DkColor.fromRGB(70, 54, 39),
    val route: DkColor = DkColor.fromRGB(87, 61, 38),
    val routeBlock: BlockStateRef = BlockStateRef("minecraft:brown_concrete"),
    val routeWidthPx: Int = 2,
    val routeDepth: Float = 0.015625f,
    val selected: DkColor = DkColor.fromRGB(252, 211, 77),
    val hovered: DkColor = DkColor.fromRGB(245, 224, 181),
    val panControls: CanvasPanControlsStyle = CanvasPanControlsStyle()
)

/**
 * A pannable 2D map composed over vanilla's cartography-table map texture.
 *
 * The view owns marker sizing, content transforms, route clipping, hover and
 * selection chrome, and every glyph phase that arbitrary two-axis panning can
 * reach. Callers provide only map data and actions.
 */
class CartographyMapView(
    id: String,
    contentSize: PxSize,
    markers: List<MapMarker>,
    routes: List<MapRoute> = emptyList(),
    private val selectedId: () -> String?,
    private val isHovered: (String) -> Boolean,
    private val onSelected: (MapMarker) -> Unit,
    onViewportChanged: () -> Unit,
    val style: CartographyMapStyle = CartographyMapStyle()
) {
    private val mapSprite = SpriteIndex.bundled.get(MAP_BACKGROUND)
    private val markerBadge = SpriteIndex.bundled.get(style.markerBadge)
    private val badgeSize = markerBadge
        ?.let { PxSize(it.width, it.height) }
        ?: style.badgeFallbackSize
    private val markerSizes = markers.associate { marker ->
        marker.id to PxSize(
            maxOf(
                badgeSize.w,
                TextMetrics.textWidthPx(marker.label) + style.labelPaddingRight * 2
            ),
            badgeSize.h + style.labelGap + TextMetrics.FONT_LINE_HEIGHT_PX
        )
    }
    private val markerById = markers.associateBy { it.id }
    private val routeSprites = CanvasDirectedEdgeSprites.bundled()

    val canvas = VirtualCanvasNode(
        id = id,
        contentSize = contentSize,
        initialPosition = CanvasInitialPosition.CENTER,
        viewportAspectRatio = 1f,
        onViewportChanged = onViewportChanged
    )
    val node: VirtualCanvasNode get() = canvas

    init {
        canvas.renderBackground = { painter, rect ->
            painter.fill(style.parchment, rect)
            mapSprite?.let { painter.iconFitted(it, rect.x, rect.y, rect.w, rect.h) }
        }
        canvas.renderUnderlay = { painter, viewport, _ ->
            painter.elevate {
                for (route in routes) {
                    val from = markerById[route.from] ?: continue
                    val to = markerById[route.to] ?: continue
                    val travelsRight = to.x >= from.x
                    val fromPortX = if (travelsRight) {
                        from.x + badgeSize.w / 2
                    } else {
                        from.x - badgeSize.w / 2
                    }
                    val toPortX = if (travelsRight) {
                        to.x - badgeSize.w / 2
                    } else {
                        to.x + badgeSize.w / 2
                    }
                    routeSprites?.let {
                        painter.canvasDirectedEdge(
                            viewport,
                            canvas.toViewportPoint(fromPortX, from.y),
                            canvas.toViewportPoint(toPortX, to.y),
                            CanvasEdgeRail(
                                style.routeBlock,
                                style.route,
                                style.routeWidthPx,
                                style.routeDepth
                            ),
                            it
                        )
                    }
                }
            }
        }

        for (marker in markers) {
            val icon = SpriteIndex.bundled.get(marker.icon)
            val markerId = "$id-marker-${marker.id}"
            val markerSize = markerSizes.getValue(marker.id)
            val child = WidgetNode(markerId, markerSize) { painter, rect ->
                val selected = selectedId() == marker.id
                val hovered = isHovered(markerId)
                val badgeX = rect.centeredX(badgeSize.w)
                painter.elevate {
                    markerBadge?.let {
                        faceIcon(
                            it,
                            badgeX,
                            rect.y,
                            depthOffset = MAP_BADGE_BIAS
                        )
                    }
                    if (icon != null) {
                        faceIcon(
                            icon,
                            badgeX + (badgeSize.w - icon.width) / 2,
                            rect.y + (badgeSize.h - icon.height) / 2,
                            depthOffset = MAP_FACE_BIAS
                        )
                    }
                    faceLabel(
                        marker.label,
                        rect.centeredX(TextMetrics.textWidthPx(marker.label)),
                        rect.y + badgeSize.h + style.labelGap,
                        when {
                            selected -> style.selected
                            hovered -> style.hovered
                            else -> marker.color
                        },
                        depthOffset = MAP_FACE_BIAS
                    )
                }
            }
            child.onEvent = { event ->
                when (event) {
                    is SurfaceEvent.Click -> {
                        onSelected(marker)
                        EventResult.CONSUMED
                    }
                    is SurfaceEvent.PointerEnter, is SurfaceEvent.PointerExit -> EventResult.CONSUMED
                    else -> EventResult.PASS
                }
            }
            child.onPrepare = {
                markerBadge?.let(SpriteGlyphs::warmAllPhases)
                if (icon != null) {
                    SpriteGlyphs.warmAllPhases(icon)
                }
            }
            canvas.addAt(
                child,
                marker.x - markerSize.w / 2,
                marker.y - badgeSize.h / 2
            )
        }

        canvas.addPanControls("$id-pan", isHovered, style.panControls)

        canvas.onPrepare = {
            mapSprite?.let {
                val rect = canvas.viewportRect()
                val height = SpriteFit.height(it, rect.w, rect.h)
                SpriteGlyphs.warmAllPhases(it, height)
            }
            markerBadge?.let(SpriteGlyphs::warmAllPhases)
            routeSprites?.entries?.forEach(SpriteGlyphs::warmAllPhases)
        }
    }

    companion object {
        val MAP_BACKGROUND = SpriteId("gui", "container/cartography_table/map")
    }
}

private const val MAP_BADGE_BIAS = 0.00005f
private const val MAP_FACE_BIAS = 0.0001f
