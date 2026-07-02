package io.schemat.displaykit.render

/**
 * Represents text content that can be displayed.
 *
 * Supports three content types:
 * - text: Plain text content
 * - sprite: Atlas sprite from Minecraft's texture atlases (1.21.5+)
 * - icon: Custom icon using font providers (requires resource pack with SpriteAssetProvider)
 *
 * Note: For overlapping/layered text displays, use multiple TextDisplay entities
 * with slight z-offset in the normal direction rather than font-based spacing.
 */
data class TextComponent(
    val text: String = "",
    val color: DkColor? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underlined: Boolean = false,
    val strikethrough: Boolean = false,
    val font: String? = null,
    val children: List<TextComponent> = emptyList(),
    // Atlas sprite support (1.21.5+)
    val sprite: Sprite? = null,
    // Custom icon support (via font providers)
    val icon: Icon? = null
) {
    operator fun plus(other: TextComponent) = copy(children = children + other)

    fun plain(): String = when {
        sprite != null -> "[sprite:${sprite.atlas}/${sprite.name}]"
        icon != null -> "[icon:${icon.id}]"
        else -> text
    } + children.joinToString("") { it.plain() }

    fun withFont(fontName: String) = copy(font = fontName)
    fun withColor(color: DkColor) = copy(color = color)

    companion object {
        val EMPTY = TextComponent()

        fun of(text: String) = TextComponent(text = text)
        fun colored(text: String, color: DkColor) = TextComponent(text = text, color = color)

        /**
         * Create a text component displaying an atlas sprite.
         * Atlas sprites are rendered as 8x8 pixel squares.
         * Bold and italic styles are ignored for sprites.
         *
         * @param atlas The texture atlas (e.g., "minecraft:gui", "minecraft:blocks", "minecraft:items")
         * @param name The sprite name within the atlas (e.g., "hud/heart/full", "block/grass_block")
         */
        fun sprite(atlas: String, name: String) = TextComponent(sprite = Sprite(atlas, name))

        /**
         * Create a text component displaying a GUI sprite.
         * Shorthand for sprite("minecraft:gui", name)
         */
        fun guiSprite(name: String) = sprite("minecraft:gui", name)

        /**
         * Create a text component displaying a block sprite.
         * Shorthand for sprite("minecraft:blocks", name)
         */
        fun blockSprite(name: String) = sprite("minecraft:blocks", name)

        /**
         * Create a text component displaying an item sprite.
         * Shorthand for sprite("minecraft:items", name)
         */
        fun itemSprite(name: String) = sprite("minecraft:items", name)

        /**
         * Create a text component displaying a custom icon.
         * Requires a resource pack with the corresponding font provider.
         *
         * @param id Icon identifier (registered via SpriteProvider)
         */
        fun icon(id: String) = TextComponent(icon = Icons.get(id) ?: Icon(id, '\uE000', "displaykit:icons"))

        /**
         * Create a text component displaying a custom icon with a fallback character.
         */
        fun icon(icon: Icon) = TextComponent(icon = icon)
    }
}

/**
 * Represents an atlas sprite from Minecraft's texture atlases.
 * Used for displaying built-in game sprites in text components.
 */
data class Sprite(
    /** The texture atlas resource location (e.g., "minecraft:gui", "minecraft:blocks") */
    val atlas: String,
    /** The sprite name within the atlas (e.g., "hud/heart/full", "block/grass_block") */
    val name: String
)

/**
 * Represents a custom icon defined via font providers.
 * Icons are mapped to Unicode characters in the Private Use Area (U+E000 to U+F8FF).
 */
data class Icon(
    /** Unique identifier for this icon */
    val id: String,
    /** Unicode character mapped to this icon's texture */
    val character: Char,
    /** Font resource location (e.g., "displaykit:icons") */
    val font: String = "displaykit:icons",
    /** Width offset for spacing (can be negative for overlapping) */
    val widthOffset: Int = 0
) {
    /** Get the Unicode character as a string */
    fun asString(): String = character.toString()
}

/**
 * Registry of custom icons available for use in text components.
 * Icons must be registered along with their corresponding font provider assets.
 *
 * Requires SpriteAssetProvider to be registered for built-in icons to work:
 * ```
 * FabricPackIntegration.registerAssetProvider(SpriteAssetProvider)
 * ```
 */
object Icons {
    private val registry = mutableMapOf<String, Icon>()
    private var nextChar = '\uE000'

    /**
     * Register a new icon.
     *
     * @param id Unique identifier
     * @param font Font containing this icon
     * @param widthOffset Horizontal spacing adjustment
     * @return The registered Icon
     */
    fun register(id: String, font: String = "displaykit:icons", widthOffset: Int = 0): Icon {
        val icon = Icon(id, nextChar++, font, widthOffset)
        registry[id] = icon
        return icon
    }

    /**
     * Register an icon with a specific character.
     */
    fun register(id: String, character: Char, font: String = "displaykit:icons", widthOffset: Int = 0): Icon {
        val icon = Icon(id, character, font, widthOffset)
        registry[id] = icon
        return icon
    }

    /**
     * Get a registered icon by ID.
     */
    fun get(id: String): Icon? = registry[id]

    /**
     * Get all registered icons.
     */
    fun all(): Collection<Icon> = registry.values

    /**
     * Clear all registered icons.
     */
    fun clear() {
        registry.clear()
        nextChar = '\uE000'
    }

    // Built-in icons (available when resource pack is loaded)
    object Builtin {
        // Chat bubble components
        @JvmField val BUBBLE_LEFT = register("bubble_left")
        @JvmField val BUBBLE_CENTER = register("bubble_center")
        @JvmField val BUBBLE_RIGHT = register("bubble_right")
        @JvmField val BUBBLE_TAIL = register("bubble_tail")

        // Badge components (white, tintable with color) - for name tags
        @JvmField val BADGE_LEFT = register("badge_left")
        @JvmField val BADGE_CENTER = register("badge_center")
        @JvmField val BADGE_RIGHT = register("badge_right")

        // Common UI elements
        @JvmField val CHECKBOX_UNCHECKED = register("checkbox_unchecked")
        @JvmField val CHECKBOX_CHECKED = register("checkbox_checked")
        @JvmField val RADIO_UNCHECKED = register("radio_unchecked")
        @JvmField val RADIO_CHECKED = register("radio_checked")
        @JvmField val ARROW_LEFT = register("arrow_left")
        @JvmField val ARROW_RIGHT = register("arrow_right")
        @JvmField val ARROW_UP = register("arrow_up")
        @JvmField val ARROW_DOWN = register("arrow_down")
        @JvmField val CLOSE = register("close")
        @JvmField val MENU = register("menu")
        @JvmField val SETTINGS = register("settings")
        @JvmField val INFO = register("info")
        @JvmField val WARNING = register("warning")
        @JvmField val ERROR = register("error")
        @JvmField val SUCCESS = register("success")

        // Spacing helpers (negative width for overlapping)
        @JvmField val SPACE_NEG_1 = register("space_neg_1", widthOffset = -1)
        @JvmField val SPACE_NEG_2 = register("space_neg_2", widthOffset = -2)
        @JvmField val SPACE_NEG_4 = register("space_neg_4", widthOffset = -4)
        @JvmField val SPACE_NEG_8 = register("space_neg_8", widthOffset = -8)
    }
}

/**
 * Common GUI sprite paths for Minecraft's built-in GUI atlas.
 * Use with TextComponent.guiSprite()
 *
 * Note: Atlas contents can be dumped in-game with F3+S.
 * Sprites render as 8x8 pixel squares in text components.
 */
object GuiSprites {
    // Hearts (9x9 pixels each)
    const val HEART_FULL = "hud/heart/full"
    const val HEART_HALF = "hud/heart/half"
    const val HEART_CONTAINER = "hud/heart/container"
    const val HEART_FULL_BLINKING = "hud/heart/full_blinking"
    const val HEART_HALF_BLINKING = "hud/heart/half_blinking"
    const val HEART_CONTAINER_BLINKING = "hud/heart/container_blinking"
    const val HEART_HARDCORE_FULL = "hud/heart/hardcore_full"
    const val HEART_HARDCORE_HALF = "hud/heart/hardcore_half"
    const val HEART_POISONED_FULL = "hud/heart/poisoned_full"
    const val HEART_POISONED_HALF = "hud/heart/poisoned_half"
    const val HEART_WITHERED_FULL = "hud/heart/withered_full"
    const val HEART_WITHERED_HALF = "hud/heart/withered_half"
    const val HEART_FROZEN_FULL = "hud/heart/frozen_full"
    const val HEART_FROZEN_HALF = "hud/heart/frozen_half"
    const val HEART_ABSORBING_FULL = "hud/heart/absorbing_full"
    const val HEART_ABSORBING_HALF = "hud/heart/absorbing_half"
    const val HEART_VEHICLE_FULL = "hud/heart/vehicle_full"
    const val HEART_VEHICLE_HALF = "hud/heart/vehicle_half"
    const val HEART_VEHICLE_CONTAINER = "hud/heart/vehicle_container"

    // Armor (9x9 pixels each)
    const val ARMOR_FULL = "hud/armor_full"
    const val ARMOR_HALF = "hud/armor_half"
    const val ARMOR_EMPTY = "hud/armor_empty"

    // Food (9x9 pixels each)
    const val FOOD_FULL = "hud/food_full"
    const val FOOD_HALF = "hud/food_half"
    const val FOOD_EMPTY = "hud/food_empty"
    const val FOOD_FULL_HUNGER = "hud/food_full_hunger"
    const val FOOD_HALF_HUNGER = "hud/food_half_hunger"
    const val FOOD_EMPTY_HUNGER = "hud/food_empty_hunger"

    // Air (9x9 pixels each)
    const val AIR = "hud/air"
    const val AIR_BURSTING = "hud/air_bursting"
    const val AIR_EMPTY = "hud/air_empty"

    // HUD elements
    const val CROSSHAIR = "hud/crosshair"  // 15x15
    const val HOTBAR = "hud/hotbar"  // 182x22
    const val HOTBAR_SELECTION = "hud/hotbar_selection"  // 24x23
    const val HOTBAR_OFFHAND_LEFT = "hud/hotbar_offhand_left"  // 29x24
    const val HOTBAR_OFFHAND_RIGHT = "hud/hotbar_offhand_right"  // 29x24
    const val EXPERIENCE_BAR_BACKGROUND = "hud/experience_bar_background"  // 182x5
    const val EXPERIENCE_BAR_PROGRESS = "hud/experience_bar_progress"  // 182x5
    const val JUMP_BAR_BACKGROUND = "hud/jump_bar_background"  // 182x5
    const val JUMP_BAR_PROGRESS = "hud/jump_bar_progress"  // 182x5

    // Widgets
    const val BUTTON = "widget/button"  // 200x20
    const val BUTTON_DISABLED = "widget/button_disabled"
    const val BUTTON_HIGHLIGHTED = "widget/button_highlighted"
    const val CHECKBOX = "widget/checkbox"  // 20x20
    const val CHECKBOX_HIGHLIGHTED = "widget/checkbox_highlighted"
    const val CHECKBOX_SELECTED = "widget/checkbox_selected"
    const val CHECKBOX_SELECTED_HIGHLIGHTED = "widget/checkbox_selected_highlighted"
    const val SLIDER = "widget/slider"  // 200x20
    const val SLIDER_HIGHLIGHTED = "widget/slider_highlighted"
    const val SLIDER_HANDLE = "widget/slider_handle"  // 8x20
    const val SLIDER_HANDLE_HIGHLIGHTED = "widget/slider_handle_highlighted"
    const val TEXT_FIELD = "widget/text_field"  // 200x20
    const val TEXT_FIELD_HIGHLIGHTED = "widget/text_field_highlighted"
    const val TAB = "widget/tab"  // 130x24
    const val TAB_SELECTED = "widget/tab_selected"
    const val TAB_HIGHLIGHTED = "widget/tab_highlighted"
    const val SCROLLER = "widget/scroller"  // 6x32
    const val SCROLLER_BACKGROUND = "widget/scroller_background"
    const val SLOT_FRAME = "widget/slot_frame"  // 80x80
    const val CROSS_BUTTON = "widget/cross_button"  // 14x14
    const val CROSS_BUTTON_HIGHLIGHTED = "widget/cross_button_highlighted"
    const val PAGE_FORWARD = "widget/page_forward"  // 23x13
    const val PAGE_FORWARD_HIGHLIGHTED = "widget/page_forward_highlighted"
    const val PAGE_BACKWARD = "widget/page_backward"
    const val PAGE_BACKWARD_HIGHLIGHTED = "widget/page_backward_highlighted"

    // Icons
    const val ICON_INFO = "icon/info"  // 20x20
    const val ICON_CHECKMARK = "icon/checkmark"  // 9x8
    const val ICON_SEARCH = "icon/search"  // 12x12
    const val ICON_ACCESSIBILITY = "icon/accessibility"  // 15x15
    const val ICON_LANGUAGE = "icon/language"
    const val ICON_LINK = "icon/link"
    const val ICON_LINK_HIGHLIGHTED = "icon/link_highlighted"
    const val ICON_NEWS = "icon/news"  // 14x14
    const val ICON_INVITE = "icon/invite"
    const val ICON_CHAT_MODIFIED = "icon/chat_modified"  // 9x9
    const val ICON_VIDEO_LINK = "icon/video_link"
    const val ICON_DRAFT_REPORT = "icon/draft_report"
    const val ICON_MUSIC_NOTES = "icon/music_notes"  // 16x16
    const val ICON_TRIAL_AVAILABLE = "icon/trial_available"  // 8x8

    // Ping indicators (10x8 pixels each)
    const val PING_1 = "icon/ping_1"
    const val PING_2 = "icon/ping_2"
    const val PING_3 = "icon/ping_3"
    const val PING_4 = "icon/ping_4"
    const val PING_5 = "icon/ping_5"
    const val PING_UNKNOWN = "icon/ping_unknown"

    // Notifications (8x8 pixels)
    const val NOTIFICATION_1 = "notification/1"
    const val NOTIFICATION_2 = "notification/2"
    const val NOTIFICATION_3 = "notification/3"
    const val NOTIFICATION_4 = "notification/4"
    const val NOTIFICATION_5 = "notification/5"
    const val NOTIFICATION_MORE = "notification/more"

    // Tooltips
    const val TOOLTIP_BACKGROUND = "tooltip/background"  // 100x100
    const val TOOLTIP_FRAME = "tooltip/frame"  // 100x100

    // Container slots
    const val SLOT = "container/slot"  // 18x18
    const val SLOT_HIGHLIGHT_BACK = "container/slot_highlight_back"  // 24x24
    const val SLOT_HIGHLIGHT_FRONT = "container/slot_highlight_front"

    // Boss bar backgrounds (182x5 pixels each)
    const val BOSS_BAR_BLUE_BACKGROUND = "boss_bar/blue_background"
    const val BOSS_BAR_BLUE_PROGRESS = "boss_bar/blue_progress"
    const val BOSS_BAR_GREEN_BACKGROUND = "boss_bar/green_background"
    const val BOSS_BAR_GREEN_PROGRESS = "boss_bar/green_progress"
    const val BOSS_BAR_RED_BACKGROUND = "boss_bar/red_background"
    const val BOSS_BAR_RED_PROGRESS = "boss_bar/red_progress"
    const val BOSS_BAR_PINK_BACKGROUND = "boss_bar/pink_background"
    const val BOSS_BAR_PINK_PROGRESS = "boss_bar/pink_progress"
    const val BOSS_BAR_PURPLE_BACKGROUND = "boss_bar/purple_background"
    const val BOSS_BAR_PURPLE_PROGRESS = "boss_bar/purple_progress"
    const val BOSS_BAR_WHITE_BACKGROUND = "boss_bar/white_background"
    const val BOSS_BAR_WHITE_PROGRESS = "boss_bar/white_progress"
    const val BOSS_BAR_YELLOW_BACKGROUND = "boss_bar/yellow_background"
    const val BOSS_BAR_YELLOW_PROGRESS = "boss_bar/yellow_progress"

    // Toast notifications
    const val TOAST_ADVANCEMENT = "toast/advancement"  // 160x32
    const val TOAST_RECIPE = "toast/recipe"
    const val TOAST_SYSTEM = "toast/system"  // 160x64
    const val TOAST_TUTORIAL = "toast/tutorial"

    // Gamemode switcher
    const val GAMEMODE_SLOT = "gamemode_switcher/slot"  // 26x26
    const val GAMEMODE_SELECTION = "gamemode_switcher/selection"

    // Spectator
    const val SPECTATOR_CLOSE = "spectator/close"  // 16x16
    const val SPECTATOR_TELEPORT_TO_PLAYER = "spectator/teleport_to_player"
    const val SPECTATOR_TELEPORT_TO_TEAM = "spectator/teleport_to_team"
    const val SPECTATOR_SCROLL_LEFT = "spectator/scroll_left"
    const val SPECTATOR_SCROLL_RIGHT = "spectator/scroll_right"

    // Social/multiplayer
    const val MUTE_BUTTON = "social_interactions/mute_button"  // 20x20
    const val UNMUTE_BUTTON = "social_interactions/unmute_button"
    const val REPORT_BUTTON = "social_interactions/report_button"

    // Pending invites
    const val PENDING_ACCEPT = "pending_invite/accept"  // 18x18
    const val PENDING_ACCEPT_HIGHLIGHTED = "pending_invite/accept_highlighted"
    const val PENDING_REJECT = "pending_invite/reject"
    const val PENDING_REJECT_HIGHLIGHTED = "pending_invite/reject_highlighted"

    // Dialog
    const val WARNING_BUTTON = "dialog/warning_button"  // 20x20
    const val WARNING_BUTTON_DISABLED = "dialog/warning_button_disabled"
    const val WARNING_BUTTON_HIGHLIGHTED = "dialog/warning_button_highlighted"
}
