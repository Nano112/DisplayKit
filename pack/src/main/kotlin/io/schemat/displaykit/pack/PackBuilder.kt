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
        val mcmeta = """
            {
              "pack": {
                "pack_format": ${config.packFormat},
                "description": "${config.packDescription}"
              }
            }
        """.trimIndent()

        zos.putNextEntry(ZipEntry("pack.mcmeta"))
        zos.write(mcmeta.toByteArray(StandardCharsets.UTF_8))
        zos.closeEntry()
    }

    /**
     * Clear all assets (for rebuilding).
     */
    fun clear(): PackBuilder {
        assets.clear()
        return this
    }

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
