package io.schemat.displaykit.pack

import java.awt.Color
import java.awt.image.BufferedImage

/**
 * Asset provider that generates custom icon fonts for DisplayKit.
 *
 * Creates bitmap font providers that map Unicode characters (U+E000 to U+F8FF)
 * to custom icon textures. This enables displaying custom icons inline in
 * text components, chat messages, and display entities.
 *
 * Usage:
 * 1. Register icons using SpriteAssetProvider.registerIcon()
 * 2. Icons are automatically included when the resource pack is built
 * 3. Use TextComponent.icon("icon_id") to display icons in text
 */
object SpriteAssetProvider : AssetProvider {

    private val icons = mutableListOf<IconDefinition>()
    private var nextChar = '\uE000'

    /**
     * Definition of a custom icon for font generation.
     */
    data class IconDefinition(
        val id: String,
        val character: Char,
        val image: BufferedImage,
        val ascent: Int = 8,
        val height: Int = 8
    )

    /**
     * Register a custom icon from an image.
     *
     * @param id Unique identifier (should match Icons.register() call)
     * @param image The icon image (typically 8x8 to 16x16 pixels)
     * @param ascent Vertical offset (usually height/2)
     * @param height Display height in pixels
     * @return The Unicode character assigned to this icon
     */
    fun registerIcon(
        id: String,
        image: BufferedImage,
        ascent: Int = 8,
        height: Int = 8
    ): Char {
        val char = nextChar++
        icons.add(IconDefinition(id, char, image, ascent, height))
        return char
    }

    /**
     * Register a simple colored square icon.
     */
    fun registerColorIcon(id: String, color: Color, size: Int = 8): Char {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = color
        g.fillRect(0, 0, size, size)
        g.dispose()
        return registerIcon(id, image, size / 2, size)
    }

    /**
     * Clear all registered icons.
     */
    fun clear() {
        icons.clear()
        nextChar = '\uE000'
    }

    override fun contributeAssets(builder: PackBuilder) {
        if (icons.isEmpty()) {
            // Still add default icons even if none were explicitly registered
            addDefaultIcons(builder)
        }

        // Generate the icon font provider
        generateIconFont(builder)
    }

    private fun addDefaultIcons(builder: PackBuilder) {
        // Chat bubble components (9-patch style for flexible width)
        registerBubbleIcons()

        // Common UI icons
        registerUIIcons()

        // Negative space characters for layout
        registerSpacingIcons(builder)
    }

    private fun registerBubbleIcons() {
        // Left cap of chat bubble (rounded left edge)
        val bubbleLeft = createBubbleLeft()
        registerIcon("bubble_left", bubbleLeft, 7, 14)

        // Center piece (repeatable)
        val bubbleCenter = createBubbleCenter()
        registerIcon("bubble_center", bubbleCenter, 7, 14)

        // Right cap (rounded right edge)
        val bubbleRight = createBubbleRight()
        registerIcon("bubble_right", bubbleRight, 7, 14)

        // Tail/pointer
        val bubbleTail = createBubbleTail()
        registerIcon("bubble_tail", bubbleTail, 7, 14)

        // Badge icons (white, can be tinted with color) - for name tags
        val badgeLeft = createBadgeLeft()
        registerIcon("badge_left", badgeLeft, 5, 10)

        val badgeCenter = createBadgeCenter()
        registerIcon("badge_center", badgeCenter, 5, 10)

        val badgeRight = createBadgeRight()
        registerIcon("badge_right", badgeRight, 5, 10)
    }

    private fun registerUIIcons() {
        // Checkbox icons (8x8)
        val checkboxUnchecked = createCheckboxUnchecked()
        registerIcon("checkbox_unchecked", checkboxUnchecked, 7, 8)

        val checkboxChecked = createCheckboxChecked()
        registerIcon("checkbox_checked", checkboxChecked, 7, 8)

        // Radio button icons (8x8)
        val radioUnchecked = createRadioUnchecked()
        registerIcon("radio_unchecked", radioUnchecked, 7, 8)

        val radioChecked = createRadioChecked()
        registerIcon("radio_checked", radioChecked, 7, 8)

        // Arrow icons (8x8)
        registerIcon("arrow_left", createArrow(Direction.LEFT), 7, 8)
        registerIcon("arrow_right", createArrow(Direction.RIGHT), 7, 8)
        registerIcon("arrow_up", createArrow(Direction.UP), 7, 8)
        registerIcon("arrow_down", createArrow(Direction.DOWN), 7, 8)

        // Close X icon
        registerIcon("close", createCloseIcon(), 7, 8)

        // Status icons
        registerIcon("info", createStatusIcon(Color(0x55, 0xAA, 0xFF), 'i'), 7, 8)
        registerIcon("warning", createStatusIcon(Color(0xFF, 0xAA, 0x00), '!'), 7, 8)
        registerIcon("error", createStatusIcon(Color(0xFF, 0x55, 0x55), 'x'), 7, 8)
        registerIcon("success", createStatusIcon(Color(0x55, 0xFF, 0x55), '✓'), 7, 8)
    }

    private fun registerSpacingIcons(builder: PackBuilder) {
        // Add a space font provider for negative/positive spacing
        // This uses the "space" provider type, not bitmap
        val spaceProviderJson = """
            {
                "providers": [
                    {
                        "type": "space",
                        "advances": {
                            "\uF001": -1,
                            "\uF002": -2,
                            "\uF004": -4,
                            "\uF008": -8,
                            "\uF010": -16,
                            "\uF020": -32,
                            "\uF040": -64,
                            "\uF080": -128,
                            "\uF101": 1,
                            "\uF102": 2,
                            "\uF104": 4,
                            "\uF108": 8,
                            "\uF110": 16,
                            "\uF120": 32,
                            "\uF140": 64,
                            "\uF180": 128,
                            "\uF200": 0
                        }
                    }
                ]
            }
        """.trimIndent()
        builder.addJson("assets/displaykit/font/spacing.json", spaceProviderJson)
    }

    private fun generateIconFont(builder: PackBuilder) {
        if (icons.isEmpty()) return

        // Group icons by their height for efficient texture packing
        val iconsByHeight = icons.groupBy { it.height }

        val providers = mutableListOf<String>()

        for ((height, heightIcons) in iconsByHeight) {
            // Create a texture atlas for this height group
            val atlasWidth = heightIcons.size * 16 // 16px per icon slot
            val atlasHeight = height.coerceAtLeast(16)

            val atlas = BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB)
            val g = atlas.createGraphics()

            val chars = StringBuilder()
            for ((index, icon) in heightIcons.withIndex()) {
                // Draw icon into atlas
                val x = index * 16
                g.drawImage(icon.image, x, 0, null)
                chars.append(icon.character)
            }
            g.dispose()

            // Add texture to pack
            val textureName = "icons_h$height"
            builder.addImage("assets/displaykit/textures/font/$textureName.png", atlas)

            // Add provider entry
            val ascent = heightIcons.first().ascent
            providers.add("""
                {
                    "type": "bitmap",
                    "file": "displaykit:font/$textureName.png",
                    "ascent": $ascent,
                    "height": $height,
                    "chars": ["$chars"]
                }
            """.trimIndent())
        }

        // Create the main icon font file
        val fontJson = """
            {
                "providers": [
                    ${providers.joinToString(",\n                    ")}
                ]
            }
        """.trimIndent()

        builder.addJson("assets/displaykit/font/icons.json", fontJson)

        // Also add a reference to the default font so icons work everywhere
        val defaultFontOverride = """
            {
                "providers": [
                    {
                        "type": "reference",
                        "id": "displaykit:icons"
                    }
                ]
            }
        """.trimIndent()
        builder.addJson("assets/minecraft/font/default.json", defaultFontOverride)
    }

    // ============= Icon Generation Helpers =============

    private enum class Direction { LEFT, RIGHT, UP, DOWN }

    private fun createBubbleLeft(): BufferedImage {
        val img = BufferedImage(8, 14, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(0, 0, 0, 180)
        // Rounded left edge
        g.fillRoundRect(-4, 0, 12, 14, 6, 6)
        g.dispose()
        return img
    }

    private fun createBubbleCenter(): BufferedImage {
        val img = BufferedImage(8, 14, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(0, 0, 0, 180)
        g.fillRect(0, 0, 8, 14)
        g.dispose()
        return img
    }

    private fun createBubbleRight(): BufferedImage {
        val img = BufferedImage(8, 14, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(0, 0, 0, 180)
        // Rounded right edge
        g.fillRoundRect(0, 0, 12, 14, 6, 6)
        g.dispose()
        return img
    }

    private fun createBubbleTail(): BufferedImage {
        val img = BufferedImage(6, 14, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(0, 0, 0, 180)
        // Triangle tail pointing down-left
        val xPoints = intArrayOf(0, 6, 6)
        val yPoints = intArrayOf(14, 8, 14)
        g.fillPolygon(xPoints, yPoints, 3)
        g.dispose()
        return img
    }

    // Badge icons (white, tintable) for name tags
    private fun createBadgeLeft(): BufferedImage {
        val img = BufferedImage(6, 10, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        // Rounded left edge - draw full rect then we only show left portion
        g.fillRoundRect(0, 0, 12, 10, 6, 6)
        g.dispose()
        return img
    }

    private fun createBadgeCenter(): BufferedImage {
        val img = BufferedImage(4, 10, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 4, 10)
        g.dispose()
        return img
    }

    private fun createBadgeRight(): BufferedImage {
        val img = BufferedImage(6, 10, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        // Rounded right edge
        g.fillRoundRect(-6, 0, 12, 10, 6, 6)
        g.dispose()
        return img
    }

    private fun createCheckboxUnchecked(): BufferedImage {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.drawRect(0, 0, 7, 7)
        g.dispose()
        return img
    }

    private fun createCheckboxChecked(): BufferedImage {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.drawRect(0, 0, 7, 7)
        // Checkmark
        g.drawLine(1, 4, 3, 6)
        g.drawLine(3, 6, 6, 1)
        g.dispose()
        return img
    }

    private fun createRadioUnchecked(): BufferedImage {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.drawOval(0, 0, 7, 7)
        g.dispose()
        return img
    }

    private fun createRadioChecked(): BufferedImage {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.drawOval(0, 0, 7, 7)
        g.fillOval(2, 2, 4, 4)
        g.dispose()
        return img
    }

    private fun createArrow(direction: Direction): BufferedImage {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE

        val xPoints: IntArray
        val yPoints: IntArray
        when (direction) {
            Direction.LEFT -> {
                xPoints = intArrayOf(6, 2, 6)
                yPoints = intArrayOf(1, 4, 7)
            }
            Direction.RIGHT -> {
                xPoints = intArrayOf(2, 6, 2)
                yPoints = intArrayOf(1, 4, 7)
            }
            Direction.UP -> {
                xPoints = intArrayOf(1, 4, 7)
                yPoints = intArrayOf(6, 2, 6)
            }
            Direction.DOWN -> {
                xPoints = intArrayOf(1, 4, 7)
                yPoints = intArrayOf(2, 6, 2)
            }
        }
        g.fillPolygon(xPoints, yPoints, 3)
        g.dispose()
        return img
    }

    private fun createCloseIcon(): BufferedImage {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        // X shape
        g.drawLine(1, 1, 6, 6)
        g.drawLine(1, 6, 6, 1)
        g.dispose()
        return img
    }

    private fun createStatusIcon(bgColor: Color, symbol: Char): BufferedImage {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = bgColor
        g.fillOval(0, 0, 8, 8)
        g.color = Color.WHITE
        // Simple text centering (approximate for small size)
        g.drawString(symbol.toString(), 2, 6)
        g.dispose()
        return img
    }
}

/**
 * Chat bubble configuration for creating flexible-width chat bubbles.
 * The actual TextComponent creation should be done via the ChatBubble
 * helper in the render package.
 */
object ChatBubbleConfig {
    enum class TailPosition {
        BOTTOM_LEFT,
        NONE
    }
}
