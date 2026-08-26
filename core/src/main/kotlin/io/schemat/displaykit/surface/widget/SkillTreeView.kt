package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.TextMetrics
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

data class SkillDefinition(
    val id: String,
    val label: String,
    val x: Int,
    val y: Int,
    val icon: SpriteId,
    val requires: List<String> = emptyList()
)

data class SkillTreeStyle(
    val nodeBadge: SpriteId = SpriteId("gui", "container/inventory/effect_background"),
    val badgeFallbackSize: PxSize = PxSize(32, 32),
    val labelGap: Int = 6,
    val labelPaddingRight: Int = 2,
    val lockedText: DkColor = DkColor.fromRGB(126, 132, 143),
    val availableText: DkColor = DkColor.fromRGB(245, 190, 54),
    val unlockedText: DkColor = DkColor.fromRGB(91, 214, 137),
    val hoveredText: DkColor = DkColor.fromRGB(255, 238, 176),
    val lockedConnection: DkColor = DkColor.fromRGB(72, 76, 84),
    val availableConnection: DkColor = DkColor.fromRGB(202, 155, 43),
    val unlockedConnection: DkColor = DkColor.fromRGB(56, 189, 105),
    val lockedConnectionBlock: BlockStateRef = BlockStateRef.BLACK_CONCRETE,
    val availableConnectionBlock: BlockStateRef = BlockStateRef.YELLOW_CONCRETE,
    val unlockedConnectionBlock: BlockStateRef = BlockStateRef.LIME_CONCRETE,
    val connectionWidthPx: Int = 3,
    val connectionDepth: Float = 0.0234375f,
    val canvasBackgroundBlock: BlockStateRef = BlockStateRef.GRAY_CONCRETE,
    val canvasBackgroundDepth: Float = 0.015625f,
    val gridColor: DkColor = DkColor.fromRGB(47, 53, 63),
    val gridSpacing: Int = 32,
    val panControls: CanvasPanControlsStyle = CanvasPanControlsStyle()
)

/**
 * An unlockable dependency tree on a draggable two-dimensional canvas.
 *
 * Node state, prerequisite evaluation, connector routing, panning, clipping,
 * hit targets and renderer preparation are composed here. An application owns
 * only its skill definitions and unlocked-id set.
 */
class SkillTreeView(
    id: String,
    contentSize: PxSize,
    skills: List<SkillDefinition>,
    private val isUnlocked: (String) -> Boolean,
    private val isHovered: (String) -> Boolean,
    private val onUnlock: (SkillDefinition) -> Unit,
    onViewportChanged: () -> Unit,
    val style: SkillTreeStyle = SkillTreeStyle()
) {
    private val byId = skills.associateBy { it.id }
    private val outgoingById = skills
        .flatMap { skill -> skill.requires.map { requirementId -> requirementId to skill.id } }
        .groupBy({ it.first }, { it.second })
    private val nodeBadge = SpriteIndex.bundled.get(style.nodeBadge)
    private val badgeSize = nodeBadge
        ?.let { PxSize(it.width, it.height) }
        ?: style.badgeFallbackSize
    private val nodeSizes = skills.associate { skill ->
        skill.id to PxSize(
            badgeSize.w + style.labelGap + TextMetrics.textWidthPx(skill.label) + style.labelPaddingRight,
            maxOf(badgeSize.h, TextMetrics.FONT_LINE_HEIGHT_PX)
        )
    }
    private val lockedIcon = SpriteIndex.bundled.get(LOCKED_ICON)
    private val edgeSprites = CanvasDirectedEdgeSprites.bundled()

    val canvas = VirtualCanvasNode(
        id = id,
        contentSize = contentSize,
        initialPosition = CanvasInitialPosition.CENTER_LEFT,
        onViewportChanged = onViewportChanged,
        renderBackground = { painter, rect ->
            painter.blockPanel(style.canvasBackgroundBlock, rect, style.canvasBackgroundDepth)
        }
    )
    val node: VirtualCanvasNode get() = canvas

    init {
        canvas.renderUnderlay = { painter, viewport, _ ->
            painter.canvasDotGrid(
                viewport,
                PxOffset(canvas.panX, canvas.panY),
                style.gridColor,
                style.gridSpacing
            )
            for (skill in skills) {
                for (requirementId in skill.requires) {
                    val requirement = byId[requirementId] ?: continue
                    val outgoing = outgoingById[requirementId].orEmpty()
                    val outgoingIndex = outgoing.indexOf(skill.id)
                    val incomingIndex = skill.requires.indexOf(requirementId)
                    val requirementSize = nodeSizes.getValue(requirement.id)
                    val skillSize = nodeSizes.getValue(skill.id)
                    val from = canvas.toViewportPoint(
                        requirement.x + requirementSize.w / 2,
                        requirement.y + edgePortOffset(outgoingIndex, outgoing.size)
                    )
                    val to = canvas.toViewportPoint(
                        skill.x - skillSize.w / 2,
                        skill.y + edgePortOffset(incomingIndex, skill.requires.size)
                    )
                    val rail = when {
                        isUnlocked(skill.id) -> CanvasEdgeRail(
                            style.unlockedConnectionBlock,
                            style.unlockedConnection,
                            style.connectionWidthPx,
                            style.connectionDepth
                        )
                        isAvailable(skill) -> CanvasEdgeRail(
                            style.availableConnectionBlock,
                            style.availableConnection,
                            style.connectionWidthPx,
                            style.connectionDepth
                        )
                        else -> CanvasEdgeRail(
                            style.lockedConnectionBlock,
                            style.lockedConnection,
                            style.connectionWidthPx,
                            style.connectionDepth
                        )
                    }
                    edgeSprites?.let {
                        painter.canvasDirectedEdge(
                            viewport,
                            from,
                            to,
                            rail,
                            it,
                            bendOffsetPx = edgeLaneOffset(outgoingIndex, outgoing.size) +
                                edgeLaneOffset(incomingIndex, skill.requires.size)
                        )
                    }
                }
            }
        }

        for (skill in skills) {
            val icon = SpriteIndex.bundled.get(skill.icon)
            val nodeId = "$id-skill-${skill.id}"
            val nodeSize = nodeSizes.getValue(skill.id)
            val child = WidgetNode(nodeId, nodeSize) { painter, rect ->
                val unlocked = isUnlocked(skill.id)
                val available = isAvailable(skill)
                val foreground = if (!unlocked && !available) lockedIcon else icon
                painter.elevate {
                    nodeBadge?.let {
                        faceIcon(
                            it,
                            rect.x,
                            rect.centeredY(it.height),
                            depthOffset = NODE_BADGE_BIAS
                        )
                    }
                    foreground?.let {
                        faceIcon(
                            it,
                            rect.x + (badgeSize.w - it.width) / 2,
                            rect.centeredY(it.height),
                            depthOffset = NODE_FACE_BIAS
                        )
                    }
                    faceLabel(
                        skill.label,
                        rect.x + badgeSize.w + style.labelGap,
                        rect.centeredY(9),
                        when {
                            isHovered(nodeId) -> style.hoveredText
                            unlocked -> style.unlockedText
                            available -> style.availableText
                            else -> style.lockedText
                        },
                        depthOffset = NODE_FACE_BIAS
                    )
                }
            }
            child.onEvent = { event ->
                when (event) {
                    is SurfaceEvent.Click -> {
                        if (isAvailable(skill)) onUnlock(skill)
                        EventResult.CONSUMED
                    }
                    is SurfaceEvent.PointerEnter, is SurfaceEvent.PointerExit -> EventResult.CONSUMED
                    else -> EventResult.PASS
                }
            }
            child.onPrepare = {
                lockedIcon?.let {
                    SpriteGlyphs.warmAllPhases(it)
                }
                icon?.let {
                    SpriteGlyphs.warmAllPhases(it)
                }
            }
            canvas.addAt(
                child,
                skill.x - nodeSize.w / 2,
                skill.y - nodeSize.h / 2
            )
        }
        canvas.addPanControls("$id-pan", isHovered, style.panControls)
        canvas.onPrepare = {
            nodeBadge?.let(SpriteGlyphs::warmAllPhases)
            edgeSprites?.entries?.forEach(SpriteGlyphs::warmAllPhases)
        }
    }

    private fun isAvailable(skill: SkillDefinition): Boolean =
        !isUnlocked(skill.id) && skill.requires.all(isUnlocked)

    private fun edgePortOffset(index: Int, count: Int): Int {
        if (count <= 1 || index < 0) return 0
        val span = (badgeSize.h - 8).coerceAtLeast(0)
        return -span / 2 + span * index / (count - 1)
    }

    private fun edgeLaneOffset(index: Int, count: Int): Int {
        if (count <= 1 || index < 0) return 0
        val spacing = style.connectionWidthPx + 2
        return (2 * index - (count - 1)) * spacing / 2
    }

    companion object {
        val LOCKED_ICON = SpriteId("gui", "container/cartography_table/locked")
    }
}

private const val NODE_BADGE_BIAS = 0.00005f
private const val NODE_FACE_BIAS = 0.0001f
