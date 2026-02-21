package io.schemat.displaykit.pack

import java.util.logging.Logger

/**
 * Asset provider that includes Geist fonts in the resource pack.
 *
 * Uses Minecraft's native TTF font provider for reliable rendering.
 * Overrides the default font with Geist Sans Regular.
 */
object GeistFontProvider : AssetProvider {
    private val LOGGER = Logger.getLogger("GeistFontProvider")

    // Font variants we support
    // resourceName = filename on classpath (case-sensitive)
    // packPath = path inside resource pack (must be lowercase per MC spec)
    enum class FontVariant(
        val resourceName: String,
        val packPath: String,
        val displayName: String
    ) {
        SANS_REGULAR("Geist-Regular.ttf", "geist_regular.ttf", "Geist Sans Regular"),
        SANS_MEDIUM("Geist-Medium.ttf", "geist_medium.ttf", "Geist Sans Medium"),
        SANS_SEMIBOLD("Geist-SemiBold.ttf", "geist_semibold.ttf", "Geist Sans SemiBold"),
        SANS_BOLD("Geist-Bold.ttf", "geist_bold.ttf", "Geist Sans Bold"),
        MONO_REGULAR("GeistMono-Regular.ttf", "geist_mono_regular.ttf", "Geist Mono Regular")
    }

    override fun contributeAssets(builder: PackBuilder) {
        LOGGER.info("Adding Geist fonts to resource pack...")

        // Include raw TTF files in the pack for Minecraft's native TTF provider
        for (variant in FontVariant.entries) {
            try {
                val resourcePath = "/fonts/${variant.resourceName}"
                val bytes = javaClass.getResourceAsStream(resourcePath)?.use { it.readBytes() }
                if (bytes != null) {
                    builder.addRaw("assets/displaykit/font/${variant.packPath}", bytes)
                    LOGGER.info("Added ${variant.displayName} (${bytes.size} bytes)")
                } else {
                    LOGGER.warning("Font resource not found: $resourcePath")
                }
            } catch (e: Exception) {
                LOGGER.severe("Failed to add ${variant.displayName}: ${e.message}")
            }
        }

        // Override default Minecraft font with Geist Sans Regular using native TTF provider
        builder.addJson("assets/minecraft/font/default.json", generateDefaultFontOverride())

        // Add a dedicated "geist" font namespace for explicit use
        builder.addJson("assets/displaykit/font/geist.json", generateGeistFontJson())

        LOGGER.info("Geist fonts added")
    }

    private fun generateDefaultFontOverride(): String {
        // Use Minecraft's native TTF provider to replace the default font with Geist
        return """
            |{
            |  "providers": [
            |    {
            |      "type": "ttf",
            |      "file": "displaykit:geist_regular.ttf",
            |      "shift": [0, 1],
            |      "size": 11.0,
            |      "oversample": 4.0
            |    }
            |  ]
            |}
        """.trimMargin()
    }

    private fun generateGeistFontJson(): String {
        // Dedicated geist font with multiple weights
        val providers = FontVariant.entries.joinToString(",\n") { variant ->
            """
            |    {
            |      "type": "ttf",
            |      "file": "displaykit:${variant.packPath}",
            |      "shift": [0, 1],
            |      "size": 11.0,
            |      "oversample": 4.0
            |    }
            """.trimMargin()
        }

        return """
            |{
            |  "providers": [
            |$providers
            |  ]
            |}
        """.trimMargin()
    }

}
