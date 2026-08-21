package io.schemat.displaykit.pack

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Builds Minecraft resource packs in-memory.
 *
 * All assets are collected and then compiled into a ZIP file
 * that can be served to players via HTTP.
 */
class PackBuilder(
    private val config: PackConfig
) {
    private val assets = mutableMapOf<String, ByteArray>()

    /** Item definition JSONs staged per path, kept so later ones can merge in. */
    private val itemDefinitions = mutableMapOf<String, MutableList<String>>()

    /**
     * Add a raw file to the pack.
     * @param path Path within the ZIP (e.g., "assets/displaykit/textures/foo.png")
     * @param content Raw bytes
     */
    fun addRaw(path: String, content: ByteArray): PackBuilder {
        assets[path] = content
        return this
    }

    /**
     * Add a text file to the pack.
     */
    fun addText(path: String, content: String): PackBuilder {
        return addRaw(path, content.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Add a JSON file to the pack.
     */
    fun addJson(path: String, json: String): PackBuilder {
        return addText(path, json)
    }

    /**
     * Add an item definition (`assets/minecraft/items/<item>.json`), MERGING it
     * with anything already staged at [path].
     *
     * An item definition is a single `minecraft:select` over
     * `minecraft:custom_model_data`, so every provider that wants a
     * CustomModelData case on the same base item is writing to the same file.
     * Plain [addRaw] would make that last-writer-wins and silently drop one
     * provider's models — the collision is real: `ItemModelAssetProvider`
     * carries nucleation's models on `leather_horse_armor` by default, which is
     * exactly the base item `SpriteSolidProvider` uses.
     *
     * Merging here rather than in either provider makes the result independent
     * of provider registration order.
     */
    fun addItemDefinition(path: String, json: String): PackBuilder {
        val staged = itemDefinitions.getOrPut(path) { mutableListOf() }
        staged.add(json)
        val content = if (staged.size == 1) staged[0] else mergeItemDefinitions(staged)
        return addRaw(path, content.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Merge item definition JSONs by combining their `cases` arrays.
     *
     * Shape (as nucleation generates it, and as this Minecraft version wants):
     * ```
     * { "model": { "type": "minecraft:select",
     *              "property": "minecraft:custom_model_data",
     *              "fallback": { "type": "minecraft:model", "model": "..." },
     *              "cases": [ { "when": "...", "model": { ... } } ] } }
     * ```
     * Individual case objects are lifted out of each input's `cases` array and
     * concatenated; the `fallback` comes from the first input.
     */
    private fun mergeItemDefinitions(jsonList: List<String>): String {
        val allCases = mutableListOf<String>()

        for (json in jsonList) {
            val casesStart = json.indexOf("\"cases\"")
            if (casesStart == -1) continue

            val arrayStart = json.indexOf('[', casesStart)
            if (arrayStart == -1) continue

            // Find matching close bracket, tracking nesting
            var depth = 0
            var i = arrayStart
            val arrayContent = StringBuilder()
            while (i < json.length) {
                val c = json[i]
                if (c == '[') depth++
                else if (c == ']') {
                    depth--
                    if (depth == 0) break
                }
                if (depth == 1 && c != '[') arrayContent.append(c)
                else if (depth > 1) arrayContent.append(c)
                i++
            }

            val content = arrayContent.toString().trim()
            if (content.isNotEmpty()) {
                allCases.add(content)
            }
        }

        // Extract fallback from first JSON
        val firstJson = jsonList[0]
        val fallbackStart = firstJson.indexOf("\"fallback\"")
        var fallback = """{ "type": "minecraft:model", "model": "minecraft:item/paper" }"""
        if (fallbackStart != -1) {
            val objStart = firstJson.indexOf('{', fallbackStart + 10)
            if (objStart != -1) {
                var depth = 0
                var i = objStart
                while (i < firstJson.length) {
                    if (firstJson[i] == '{') depth++
                    else if (firstJson[i] == '}') {
                        depth--
                        if (depth == 0) {
                            fallback = firstJson.substring(objStart, i + 1)
                            break
                        }
                    }
                    i++
                }
            }
        }

        return """{
  "model": {
    "type": "minecraft:select",
    "property": "minecraft:custom_model_data",
    "fallback": $fallback,
    "cases": [${allCases.joinToString(", ")}]
  }
}"""
    }

    /**
     * Add an image to the pack.
     */
    fun addImage(path: String, image: BufferedImage): PackBuilder {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        return addRaw(path, baos.toByteArray())
    }

    /**
     * Add a shader file.
     * @param type "core", "post", or "include"
     * @param name Shader name (e.g., "rendertype_text_background" or "dk_sdf")
     * @param extension "vsh", "fsh", or "glsl"
     * @param content Shader source code
     */
    fun addShader(type: String, name: String, extension: String, content: String): PackBuilder {
        val path = "assets/${config.namespace}/shaders/$type/$name.$extension"
        return addText(path, content)
    }

    /**
     * Add a shader to the minecraft namespace (for overrides).
     */
    fun addMinecraftShader(type: String, name: String, extension: String, content: String): PackBuilder {
        val path = "assets/minecraft/shaders/$type/$name.$extension"
        return addText(path, content)
    }

    /**
     * Add a post-effect pipeline definition.
     */
    fun addPostEffect(name: String, json: String): PackBuilder {
        val path = "assets/minecraft/post_effect/$name.json"
        return addJson(path, json)
    }

    /**
     * Add a texture.
     * @param category Texture category (e.g., "font", "effect", "gui")
     * @param name Texture name without extension
     * @param image The texture image
     */
    fun addTexture(category: String, name: String, image: BufferedImage): PackBuilder {
        val path = "assets/${config.namespace}/textures/$category/$name.png"
        return addImage(path, image)
    }

    /**
     * Add a font provider definition.
     */
    fun addFont(name: String, json: String): PackBuilder {
        val path = "assets/${config.namespace}/font/$name.json"
        return addJson(path, json)
    }

    /**
     * Build the resource pack as a ZIP file.
     * @return The ZIP file as bytes
     */
    fun build(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // Add pack.mcmeta
            addPackMeta(zos)

            // Add all collected assets
            for ((path, content) in assets) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun addPackMeta(zos: ZipOutputStream) {
        val format = config.packFormat
        val mcmeta = if (format > 64) {
            """
            {
              "pack": {
                "pack_format": $format,
                "min_format": 1,
                "max_format": $format,
                "supported_formats": {"min_inclusive": 1, "max_inclusive": $format},
                "description": "${config.packDescription}"
              }
            }
            """.trimIndent()
        } else {
            """
            {
              "pack": {
                "pack_format": $format,
                "description": "${config.packDescription}"
              }
            }
            """.trimIndent()
        }

        zos.putNextEntry(ZipEntry("pack.mcmeta"))
        zos.write(mcmeta.toByteArray(StandardCharsets.UTF_8))
        zos.closeEntry()
    }

    /**
     * Clear all assets (for rebuilding).
     */
    fun clear(): PackBuilder {
        assets.clear()
        itemDefinitions.clear()
        return this
    }

    /** JSON previously added at [path], for tests and diagnostics. */
    fun capturedJson(path: String): String =
        assets[path]?.toString(Charsets.UTF_8)
            ?: error("no entry at $path; have: ${assets.keys.sorted()}")

    /** Number of PNG entries staged so far. */
    fun imageCount(): Int = assets.keys.count { it.endsWith(".png") }

    companion object {
        /**
         * Compute SHA-1 hash of pack data.
         */
        fun computeSha1(data: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-1")
            return md.digest(data)
        }

        /**
         * Convert SHA-1 bytes to hex string.
         */
        fun sha1ToHex(sha1: ByteArray): String {
            return sha1.joinToString("") { "%02x".format(it) }
        }
    }
}
