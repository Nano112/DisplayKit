package io.schemat.displaykit.render

/**
 * Chat bubble builder for creating flexible-width chat bubbles.
 *
 * Creates text components that render as chat bubbles when the
 * DisplayKit resource pack is loaded with SpriteAssetProvider.
 * Bubbles are composed of left cap + content + right cap icons.
 *
 * IMPORTANT: Requires SpriteAssetProvider to be registered:
 * ```
 * FabricPackIntegration.registerAssetProvider(SpriteAssetProvider)
 * ```
 *
 * Usage:
 * ```
 * val bubble = ChatBubble.create("Hello, world!")
 * player.sendMessage(bubble)
 * ```
 */
object ChatBubble {

    /**
     * Create a chat bubble text component with the given content.
     * The bubble stretches to fit the content.
     *
     * @param content The text content inside the bubble
     * @param textColor Optional color for the text
     * @param tailPosition Position of the bubble tail/pointer
     * @return A TextComponent representing the full bubble
     */
    fun create(
        content: String,
        textColor: DkColor? = null,
        tailPosition: TailPosition = TailPosition.BOTTOM_LEFT
    ): TextComponent {
        // Build the bubble using icon composition
        val left = TextComponent.icon(Icons.Builtin.BUBBLE_LEFT)
        val center = TextComponent.of(content).let {
            if (textColor != null) it.withColor(textColor) else it
        }
        val right = TextComponent.icon(Icons.Builtin.BUBBLE_RIGHT)

        val bubble = left + center + right

        // Add tail if requested
        return when (tailPosition) {
            TailPosition.BOTTOM_LEFT -> bubble + Spacing.neg(8) + TextComponent.icon(Icons.Builtin.BUBBLE_TAIL)
            TailPosition.NONE -> bubble
        }
    }

    /**
     * Create a chat bubble with a styled content component.
     */
    fun create(
        content: TextComponent,
        tailPosition: TailPosition = TailPosition.BOTTOM_LEFT
    ): TextComponent {
        val left = TextComponent.icon(Icons.Builtin.BUBBLE_LEFT)
        val right = TextComponent.icon(Icons.Builtin.BUBBLE_RIGHT)

        val bubble = left + content + right

        return when (tailPosition) {
            TailPosition.BOTTOM_LEFT -> bubble + Spacing.neg(8) + TextComponent.icon(Icons.Builtin.BUBBLE_TAIL)
            TailPosition.NONE -> bubble
        }
    }

    /**
     * Create a simple notification-style bubble (no tail).
     */
    fun notification(content: String, textColor: DkColor? = null): TextComponent {
        return create(content, textColor, TailPosition.NONE)
    }

    enum class TailPosition {
        BOTTOM_LEFT,
        NONE
    }
}

/**
 * Extension function to wrap text in a chat bubble.
 */
fun TextComponent.inBubble(tailPosition: ChatBubble.TailPosition = ChatBubble.TailPosition.BOTTOM_LEFT): TextComponent {
    return ChatBubble.create(this, tailPosition)
}

/**
 * Extension function to wrap text in a notification bubble (no tail).
 */
fun TextComponent.inNotification(): TextComponent {
    return ChatBubble.create(this, ChatBubble.TailPosition.NONE)
}
