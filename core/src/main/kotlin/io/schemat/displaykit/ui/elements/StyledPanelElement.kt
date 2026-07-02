package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement

/**
 * A panel element that uses text_display background for rendering.
 *
 * Unlike PanelElement (which uses block displays), this element supports:
 * - Rounded corners via alpha channel encoding
 * - Glassmorphism effects
 * - Custom colors without needing a matching block type
 *
 * The panel is rendered as a text_display with empty/whitespace text
 * and a sized background, allowing our shaders to apply visual effects.
 */
class StyledPanelElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    val width: Float,
    val height: Float,
    var backgroundColor: DkColor,
    hitboxWidth: Double = 0.0,
    hitboxHeight: Double = 0.0,
    isInteractive: Boolean = false
) : UIElement(
    ui, localOffset,
    isInteractive = isInteractive,
    hitboxWidth = hitboxWidth,
    hitboxHeight = hitboxHeight
) {
    private var textDisplay: VirtualTextDisplay? = null

    companion object {
        // Minecraft text rendering constants (at scale 1.0)
        // 1 text pixel ≈ 0.025 blocks
        private const val PIXEL_SIZE = 0.025f
        // Space width in Geist font ≈ 4px (TTF fonts define proper space metrics)
        private const val CHAR_WIDTH_PX = 4
        private const val LINE_HEIGHT_PX = 10
        private const val SCALE = 0.1f
    }

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)

        // Calculate pixel dimensions needed for desired world-space size
        val neededWidthPx = (width / (PIXEL_SIZE * SCALE)).toInt()
        val neededHeightPx = (height / (PIXEL_SIZE * SCALE)).toInt()

        // Use space characters for sizing. Geist TTF provides proper space width.
        // Spaces have no visible glyph, preventing z-fighting between text and background.
        val charsPerLine = (neededWidthPx / CHAR_WIDTH_PX).coerceAtLeast(1)
        val linesNeeded = (neededHeightPx / LINE_HEIGHT_PX).coerceAtLeast(1)
        val panelLineWidth = neededWidthPx + CHAR_WIDTH_PX

        val fillLine = " ".repeat(charsPerLine)
        val paddingText = (1..linesNeeded).joinToString("\n") { fillLine }

        // Alpha encoding passes through to the shader:
        // - 200-249: Rounded corners (shader decodes radius)
        // - 100-149: Glass (semi-transparent)
        // - 250-255: Solid (full opacity)
        val effectiveColor = backgroundColor

        val display = VirtualTextDisplay().apply {
            position = pos
            text = TextComponent.of(paddingText)
            billboard = Billboard.FIXED
            backgroundColor = effectiveColor
            // text_opacity=1 keeps MC rendering the background while text is invisible.
            // text_opacity=0 causes MC to skip the entire render including background.
            textOpacity = 1
            hasShadow = false
            isSeeThrough = false
            brightness = Brightness.FULL
            textAlignment = TextAlignment.CENTER
            viewRange = 1.0f
            lineWidth = panelLineWidth
            // text_display anchors its block at the BOTTOM of the text (grows
            // upward), so shift down by half the height to center the quad on
            // the element position — matching how every consumer places panels.
            transformation = ui.buildUIElementMatrix(
                localOffsetX = 0f, localOffsetY = -height / 2f, localOffsetZ = 0f,
                scaleX = SCALE, scaleY = SCALE, scaleZ = 0.01f
            )
        }
        textDisplay = display
        spawnEntity(display)
    }

    override fun destroy() {
        destroyAllEntities()
        textDisplay = null
    }

    override fun onHoverChanged() {
        // Can be extended for hover effects
    }

    /**
     * Update the background color (e.g., for hover effects).
     */
    fun updateBackgroundColor(color: DkColor) {
        backgroundColor = color
        textDisplay?.let { display ->
            display.backgroundColor = color
            updateEntity(display)
        }
    }
}
