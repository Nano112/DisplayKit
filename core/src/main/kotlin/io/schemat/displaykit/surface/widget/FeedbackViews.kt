package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.action.AsyncAction
import io.schemat.displaykit.action.ConfirmAction
import io.schemat.displaykit.action.OperationStatus
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.WidgetNode
import java.util.concurrent.CompletionStage

enum class StatusTone { INFO, SUCCESS, WARNING, ERROR }

data class StatusBannerModel(
    val message: String,
    val tone: StatusTone = StatusTone.INFO,
    val visible: Boolean = message.isNotBlank(),
)

data class StatusBannerStyle(
    val width: Int = 240,
    val height: Int = 18,
    val textColor: DkColor = DkColor.WHITE,
    val infoColor: DkColor = DkColor(225, 35, 45, 62),
    val successColor: DkColor = DkColor(225, 24, 70, 42),
    val warningColor: DkColor = DkColor(225, 92, 70, 18),
    val errorColor: DkColor = DkColor(225, 92, 24, 30),
) {
    init {
        require(width > 0) { "Status banner width must be positive" }
        require(height >= TextMetrics.FONT_LINE_HEIGHT_PX) { "Status banner must fit one text line" }
    }
}

/** Compact, retained feedback surface with consistent semantic colour. */
class StatusBannerView(
    id: String,
    private val model: () -> StatusBannerModel,
    val style: StatusBannerStyle = StatusBannerStyle(),
) {
    val node = WidgetNode(id, PxSize(style.width, style.height)) { painter, rect ->
        val current = model()
        if (!current.visible || current.message.isBlank()) return@WidgetNode
        val color = when (current.tone) {
            StatusTone.INFO -> style.infoColor
            StatusTone.SUCCESS -> style.successColor
            StatusTone.WARNING -> style.warningColor
            StatusTone.ERROR -> style.errorColor
        }
        painter.fill(color, rect)
        val text = TextMetrics.ellipsize(current.message, rect.w - 8)
        painter.faceLabel(
            text,
            rect.x + 4,
            TextMetrics.rowAlignedY(rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2),
            style.textColor,
        )
    }
}

data class EmptyStateModel(val title: String, val message: String = "") {
    init { require(title.isNotBlank()) { "Empty-state title cannot be blank" } }
}

data class EmptyStateStyle(
    val width: Int = 240,
    val height: Int = 38,
    val titleColor: DkColor = DkColor.WHITE,
    val messageColor: DkColor = DkColor.fromRGB(170, 170, 170),
) {
    init {
        require(width > 0) { "Empty-state width must be positive" }
        require(height >= TextMetrics.FONT_LINE_HEIGHT_PX * 2) { "Empty state must fit two text lines" }
    }
}

/** Consistent no-data presentation for lists, searches and filtered canvases. */
class EmptyStateView(
    id: String,
    private val model: () -> EmptyStateModel,
    val style: EmptyStateStyle = EmptyStateStyle(),
) {
    val node = WidgetNode(id, PxSize(style.width, style.height)) { painter, rect ->
        val current = model()
        val title = TextMetrics.ellipsize(current.title, rect.w)
        painter.faceLabel(
            title,
            rect.x + (rect.w - TextMetrics.textWidthPx(title)) / 2,
            TextMetrics.rowAlignedY(rect.y + 4),
            style.titleColor,
        )
        if (current.message.isNotBlank()) {
            val message = TextMetrics.ellipsize(current.message, rect.w)
            painter.faceLabel(
                message,
                rect.x + (rect.w - TextMetrics.textWidthPx(message)) / 2,
                TextMetrics.rowAlignedY(rect.y + rect.h - TextMetrics.FONT_LINE_HEIGHT_PX - 4),
                style.messageColor,
            )
        }
    }
}

/**
 * One-button composition of confirmation and asynchronous operation state.
 * The shared [AsyncAction] owns overlap rejection and stale completion; the
 * optional [ConfirmAction] owns the second-click policy and expiry.
 */
class AsyncActionButtonView<T>(
    id: String,
    private val label: () -> String,
    private val action: AsyncAction<T>,
    private val task: () -> CompletionStage<T>,
    private val isHovered: (String) -> Boolean,
    private val confirmation: ConfirmAction? = null,
    private val visible: () -> Boolean = { true },
    private val enabled: () -> Boolean = { true },
    private val confirmLabel: () -> String = { "Confirm ${label()}" },
    private val busyLabel: () -> String = { "Working…" },
    private val retryLabel: () -> String = label,
    private val startMessage: () -> String? = busyLabel,
    val style: ActionButtonStyle = ActionButtonStyle(),
) {
    val node = ActionButtonView(
        id = id,
        label = {
            when (val status = action.status.value) {
                is OperationStatus.Running -> status.message ?: busyLabel()
                is OperationStatus.Failed, OperationStatus.Cancelled -> retryLabel()
                else -> if (confirmation?.isArmed == true) confirmLabel() else label()
            }
        },
        visible = visible,
        enabled = { enabled() && !action.isRunning },
        selected = { confirmation?.isArmed == true || action.isRunning },
        style = style,
        isHovered = isHovered,
        onClick = {
            val start = { action.start(startMessage(), task) }
            confirmation?.trigger { start() } ?: start()
        },
    ).node
}

/** Convert async state to a shared status banner without feature-local branching. */
fun OperationStatus<*>.toStatusBanner(
    idleMessage: String = "",
    successMessage: String = "Complete",
): StatusBannerModel = when (this) {
    OperationStatus.Idle -> StatusBannerModel(idleMessage)
    is OperationStatus.Running -> StatusBannerModel(message ?: "Working…", StatusTone.INFO)
    is OperationStatus.Succeeded<*> -> StatusBannerModel(message ?: successMessage, StatusTone.SUCCESS)
    is OperationStatus.Failed -> StatusBannerModel(message ?: "Operation failed", StatusTone.ERROR)
    OperationStatus.Cancelled -> StatusBannerModel("Cancelled", StatusTone.WARNING)
}
