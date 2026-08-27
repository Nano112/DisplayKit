package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.action.ActionMenuSession
import io.schemat.displaykit.action.ActionIcon
import io.schemat.displaykit.action.ActionInteraction
import io.schemat.displaykit.action.ActionSource
import io.schemat.displaykit.action.ActionSpec
import io.schemat.displaykit.action.ActionTrigger
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.BlockButton
import io.schemat.displaykit.surface.EventResult
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.PointerButton
import io.schemat.displaykit.surface.blockButton
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode
import java.util.UUID

/**
 * Text for the menu's own navigation buttons.
 *
 * The toolkit has no idea which language its viewer reads, so these default
 * to English and a consumer that does know hands over translated ones. The
 * paging labels are the bare word: the renderer appends the page numbers.
 */
data class ActionMenuLabels @JvmOverloads constructor(
    val back: String = "Back",
    val close: String = "Close",
    val previous: String = "Previous",
    val next: String = "Next",
)

data class ActionMenuViewStyle @JvmOverloads constructor(
    val width: Int = BlockButton.widthFor(BlockButton.MIN_WIDTH),
    val pageSize: Int = 6,
    /**
     * Read on every repaint rather than captured, so a consumer whose viewer
     * changes language mid-menu can return new text without rebuilding the
     * surface. Must not block: this is called from painting.
     */
    val labels: () -> ActionMenuLabels = { DEFAULT_LABELS },
    val gap: Int = 2,
    val buttonBase: BlockStateRef = BlockStateRef("minecraft:polished_blackstone"),
    val selectedBase: BlockStateRef = BlockStateRef("minecraft:gilded_blackstone"),
    val disabledBase: BlockStateRef = BlockStateRef("minecraft:gray_concrete"),
    val navigationBase: BlockStateRef = BlockStateRef("minecraft:deepslate_tiles"),
    val baseThickness: Float = 0.0625f,
    /** Hide Back/Close when the menu is embedded as a non-owning palette. */
    val showBackNavigation: Boolean = true,
) {
    init {
        require(width >= BlockButton.MIN_WIDTH) {
            "Action menu width must fit the vanilla button sprite (${BlockButton.MIN_WIDTH}px minimum)."
        }
        require(pageSize > 0) { "Action menu pageSize must be positive." }
        require(gap >= 0) { "Action menu gap cannot be negative." }
        require(baseThickness > 0f) { "Action menu button thickness must be positive." }
    }

    private companion object {
        val DEFAULT_LABELS = ActionMenuLabels()
    }
}

/**
 * Surface renderer for [ActionMenuSession].
 *
 * The tree has a fixed set of stable row nodes; each paint and event resolves
 * the current action window by ID. Page changes therefore update existing
 * entities instead of replacing the complete toolbar. [onStateChanged]
 * should repaint the owning surface session.
 */
class ActionMenuView(
    id: String,
    private val actions: ActionMenuSession,
    private val isHovered: (String) -> Boolean,
    private val onStateChanged: () -> Unit,
    private val actorId: UUID? = null,
    val style: ActionMenuViewStyle = ActionMenuViewStyle()
) {
    val node = FlexNode(id, FlexDirection.COLUMN, gap = style.gap)

    init {
        node.width = style.width
        node.addChild(titleNode("$id-title"))
        repeat(style.pageSize) { index ->
            node.addChild(actionNode("$id-action-$index", index))
        }
        node.addChild(navigationNode("$id-previous", Navigation.PREVIOUS))
        node.addChild(navigationNode("$id-next", Navigation.NEXT))
        node.addChild(navigationNode("$id-back", Navigation.BACK))
        node.onPrepare = {
            actions.currentPage.actions.mapNotNull { spriteFor(it.icon) }
                .distinctBy { it.id }
                .forEach(SpriteGlyphs::warmAllPhases)
        }
    }

    private fun titleNode(nodeId: String) = WidgetNode(
        nodeId,
        PxSize(style.width, BlockButton.HEIGHT)
    ) { painter, rect ->
        painter.fill(DkColor(255, 32, 34, 40), rect)
        val title = actions.currentPage.title ?: actions.currentPage.id.substringAfterLast('/')
        painter.faceLabel(
            title,
            rect.x + 4,
            TextMetrics.rowAlignedY(rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2)
        )
    }

    private fun actionNode(nodeId: String, index: Int): WidgetNode {
        val node = WidgetNode(nodeId, PxSize(style.width, BlockButton.HEIGHT)) { painter, rect ->
            val action = actionAt(index) ?: return@WidgetNode
            val state = when {
                !action.invokable -> BlockButton.State.SELECTED
                action.selected && isHovered(nodeId) -> BlockButton.State.SELECTED_HOVERED
                action.selected -> BlockButton.State.SELECTED
                isHovered(nodeId) -> BlockButton.State.HOVERED
                else -> BlockButton.State.NORMAL
            }
            val base = when {
                !action.invokable -> style.disabledBase
                action.selected -> style.selectedBase
                else -> style.buttonBase
            }
            val fittedLabel = TextMetrics.ellipsize(
                if (action.busy) "${action.label}…" else action.label,
                (rect.w - if (spriteFor(action.icon) != null) 48 else 8).coerceAtLeast(0)
            )
            painter.blockButton(
                id = nodeId,
                rect = rect,
                text = fittedLabel,
                state = state,
                base = base,
                baseThickness = style.baseThickness
            )
            spriteFor(action.icon)?.let { icon ->
                painter.elevate {
                    faceIcon(
                        icon,
                        rect.x + 4,
                        rect.centeredY(icon.height),
                        depthOffset = 0.0001f
                    )
                }
            }
        }
        node.onEvent = { event ->
            val action = actionAt(index)
            when (event) {
                is SurfaceEvent.PointerEnter -> {
                    actions.focus(
                        action?.id,
                        ActionInteraction(ActionSource.WORLD_SURFACE, ActionTrigger.POINTER_FOCUS, actorId)
                    )
                    onStateChanged()
                    EventResult.CONSUMED
                }
                is SurfaceEvent.PointerExit -> {
                    if (actions.focusedActionId == action?.id) {
                        actions.focus(
                            null,
                            ActionInteraction(ActionSource.WORLD_SURFACE, ActionTrigger.POINTER_FOCUS, actorId)
                        )
                    }
                    onStateChanged()
                    EventResult.CONSUMED
                }
                is SurfaceEvent.Click -> {
                    if (action == null) {
                        EventResult.PASS
                    } else {
                        actions.invoke(
                            action.id,
                            ActionInteraction(
                                ActionSource.WORLD_SURFACE,
                                if (event.button == PointerButton.RIGHT) {
                                    ActionTrigger.SECONDARY_CLICK
                                } else {
                                    ActionTrigger.PRIMARY_CLICK
                                },
                                actorId
                            )
                        )
                        onStateChanged()
                        EventResult.CONSUMED
                    }
                }
                else -> EventResult.PASS
            }
        }
        return node
    }

    private enum class Navigation { PREVIOUS, NEXT, BACK }

    private fun navigationNode(nodeId: String, navigation: Navigation): WidgetNode {
        val node = WidgetNode(nodeId, PxSize(style.width, BlockButton.HEIGHT)) { painter, rect ->
            val window = actions.window(style.pageSize)
            val visible = when (navigation) {
                Navigation.PREVIOUS -> window.hasPrevious
                Navigation.NEXT -> window.hasNext
                Navigation.BACK -> style.showBackNavigation
            }
            if (!visible) return@WidgetNode
            val labels = style.labels()
            val label = when (navigation) {
                Navigation.PREVIOUS -> "${labels.previous} (${window.pageIndex + 1}/${window.pageCount})"
                Navigation.NEXT -> "${labels.next} (${window.pageIndex + 1}/${window.pageCount})"
                Navigation.BACK -> if (actions.depth > 1) labels.back else labels.close
            }
            painter.blockButton(
                id = nodeId,
                rect = rect,
                text = label,
                state = if (isHovered(nodeId)) BlockButton.State.HOVERED else BlockButton.State.NORMAL,
                base = style.navigationBase,
                baseThickness = style.baseThickness
            )
        }
        node.onEvent = navigationEvent@ { event ->
            if (event !is SurfaceEvent.Click) return@navigationEvent EventResult.PASS
            if (navigation == Navigation.BACK && !style.showBackNavigation) {
                return@navigationEvent EventResult.PASS
            }
            val changed = when (navigation) {
                Navigation.PREVIOUS -> actions.previousPage(style.pageSize)
                Navigation.NEXT -> actions.nextPage(style.pageSize)
                Navigation.BACK -> actions.pop()
            }
            if (changed) onStateChanged()
            if (changed) EventResult.CONSUMED else EventResult.PASS
        }
        return node
    }

    private fun actionAt(index: Int): ActionSpec? =
        if (actions.isClosed) null else actions.window(style.pageSize).actions.getOrNull(index)

    private fun spriteFor(icon: ActionIcon): SpriteEntry? = when (icon) {
        ActionIcon.Default -> null
        is ActionIcon.Sprite -> SpriteIndex.bundled.get(icon.id) ?: spriteFor(icon.fallback)
        is ActionIcon.Item -> SpriteIndex.bundled.get(
            SpriteId("items", "item/${icon.item.itemId.substringAfter(':')}")
        )
        is ActionIcon.Block -> SpriteIndex.bundled.get(
            SpriteId("blocks", "block/${icon.state.id.substringBefore('[').substringAfter(':')}")
        )
        is ActionIcon.Text -> spriteFor(icon.fallback)
    }
}
