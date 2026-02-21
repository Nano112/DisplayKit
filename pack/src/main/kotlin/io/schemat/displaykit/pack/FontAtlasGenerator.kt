package io.schemat.displaykit.pack

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.InputStream

/**
 * Configuration for font atlas generation.
 */
data class FontConfig(
    val size: Float = 64f,
    val padding: Int = 4,
    val charStart: Char = ' ',
    val charEnd: Char = '~',
    val antialiasing: Boolean = true
)

/**
 * Metrics for a single glyph.
 */
data class GlyphMetrics(
    val char: Char,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val advance: Float
)

/**
 * Result of font atlas generation.
 */
data class FontAtlasResult(
    val texture: BufferedImage,
    val glslInclude: String,
    val fontProviderJson: String,
    val metrics: Map<Char, GlyphMetrics>,
    val cellWidth: Int,
    val cellHeight: Int,
    val cols: Int,
    val rows: Int
)

/**
 * Generates font texture atlases from TTF files.
 *
 * Creates:
 * - PNG texture atlas with all glyphs
 * - GLSL include with atlas constants
 * - Font provider JSON for Minecraft
 */
object FontAtlasGenerator {

    /**
     * Generate a font atlas from a TTF input stream.
     */
    fun generate(
        ttfStream: InputStream,
        name: String,
        namespace: String = "displaykit",
        config: FontConfig = FontConfig()
    ): FontAtlasResult {
        // Load font
        val baseFont = Font.createFont(Font.TRUETYPE_FONT, ttfStream)
        val font = baseFont.deriveFont(Font.PLAIN, config.size)

        // Calculate character count and grid size
        val charCount = config.charEnd.code - config.charStart.code + 1
        val cols = 16
        val rows = (charCount + cols - 1) / cols

        // Measure cell size
        val (cellWidth, cellHeight, ascent) = measureCellSize(font, config)

        // Create texture
        val texWidth = cols * cellWidth
        val texHeight = rows * cellHeight
        val texture = BufferedImage(texWidth, texHeight, BufferedImage.TYPE_INT_ARGB)

        val g = texture.createGraphics()
        setupGraphics(g, config)
        g.font = font
        g.color = Color.WHITE

        // Render glyphs and collect metrics
        val metrics = mutableMapOf<Char, GlyphMetrics>()
        val fm = g.fontMetrics

        for (i in 0 until charCount) {
            val char = (config.charStart.code + i).toChar()
            val col = i % cols
            val row = i / cols

            val x = col * cellWidth + config.padding
            val y = row * cellHeight + config.padding + ascent

            // Draw glyph
            g.drawString(char.toString(), x, y)

            // Store metrics
            metrics[char] = GlyphMetrics(
                char = char,
                x = col * cellWidth,
                y = row * cellHeight,
                width = cellWidth,
                height = cellHeight,
                advance = fm.charWidth(char).toFloat()
            )
        }

        g.dispose()

        // Generate GLSL include
        val glslInclude = generateGlslInclude(name, cols, rows, cellWidth, cellHeight, texWidth, texHeight)

        // Generate font provider JSON
        val fontProviderJson = generateFontProviderJson(name, namespace, config, cols)

        return FontAtlasResult(
            texture = texture,
            glslInclude = glslInclude,
            fontProviderJson = fontProviderJson,
            metrics = metrics,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            cols = cols,
            rows = rows
        )
    }

    private fun measureCellSize(font: Font, config: FontConfig): Triple<Int, Int, Int> {
        val tmp = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = tmp.createGraphics()
        g.font = font
        val fm = g.fontMetrics

        val ascent = fm.ascent
        val descent = fm.descent
        val cellHeight = ascent + descent + config.padding * 2

        // Use 'M' width as cell width (safe for monospace, reasonable for proportional)
        val cellWidth = fm.charWidth('M') + config.padding * 2

        g.dispose()
        return Triple(cellWidth, cellHeight, ascent)
    }

    private fun setupGraphics(g: Graphics2D, config: FontConfig) {
        if (config.antialiasing) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        } else {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
        }
    }

    private fun generateGlslInclude(
        name: String,
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        texWidth: Int,
        texHeight: Int
    ): String {
        val prefix = name.uppercase().replace("-", "_")
        return """
            |// Auto-generated font atlas constants for $name
            |#define ${prefix}_COLS $cols
            |#define ${prefix}_ROWS $rows
            |#define ${prefix}_CELL_W ${cellWidth}.0
            |#define ${prefix}_CELL_H ${cellHeight}.0
            |#define ${prefix}_TEX_W ${texWidth}.0
            |#define ${prefix}_TEX_H ${texHeight}.0
        """.trimMargin()
    }

    private fun generateFontProviderJson(
        name: String,
        namespace: String,
        config: FontConfig,
        cols: Int
    ): String {
        // Build character rows for the provider
        val charRows = mutableListOf<String>()
        var currentRow = StringBuilder()

        for (code in config.charStart.code..config.charEnd.code) {
            val char = code.toChar()
            // Escape special JSON characters
            val escaped = when (char) {
                '"' -> "\\\""
                '\\' -> "\\\\\\\\"
                else -> char.toString()
            }
            currentRow.append(escaped)

            if (currentRow.length == cols) {
                charRows.add(currentRow.toString())
                currentRow = StringBuilder()
            }
        }

        // Add remaining characters
        if (currentRow.isNotEmpty()) {
            charRows.add(currentRow.toString())
        }

        val charsJson = charRows.joinToString(",\n        ") { "\"$it\"" }

        return """
            |{
            |  "providers": [
            |    {
            |      "type": "bitmap",
            |      "file": "$namespace:font/$name.png",
            |      "ascent": 7,
            |      "height": 9,
            |      "chars": [
            |        $charsJson
            |      ]
            |    }
            |  ]
            |}
        """.trimMargin()
    }
}
