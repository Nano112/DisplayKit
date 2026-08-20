package io.schemat.displaykit.pack.gen

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * Builds the sprite index by reading a Minecraft client jar's atlas
 * definitions and measuring every referenced texture.
 *
 * Run via `:libs:displaykit:pack:generateSpriteIndex`. The output is committed
 * to `core/src/main/resources/displaykit/sprites.json`, so normal builds and
 * CI never need a client jar.
 */
object SpriteIndexGenerator {

    private const val ATLAS_DIR = "assets/minecraft/atlases/"
    private const val TEXTURE_DIR = "assets/minecraft/textures/"

    data class Stats(val indexed: Int, val animated: Int, val nineSlice: Int, val skippedSources: Int)

    var lastStats: Stats = Stats(0, 0, 0, 0)
        private set

    fun generate(clientJar: Path, mcVersion: String): String {
        val sprites = ArrayList<JsonObject>()
        var animated = 0
        var nineSlice = 0
        var skipped = 0

        ZipFile(clientJar.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()

            val atlases = names.filter { it.startsWith(ATLAS_DIR) && it.endsWith(".json") }.sorted()
            for (atlasPath in atlases) {
                val atlasName = atlasPath.removePrefix(ATLAS_DIR).removeSuffix(".json")
                val atlasJson = zip.getInputStream(zip.getEntry(atlasPath)).use {
                    JsonParser.parseString(it.reader().readText()).asJsonObject
                }

                for (sourceElement in atlasJson.getAsJsonArray("sources")) {
                    val source = sourceElement.asJsonObject
                    val type = source.get("type").asString
                    val prefix = source.get("prefix")?.asString ?: ""

                    val texturePaths: List<String> = when (type) {
                        "minecraft:directory" -> {
                            val dir = TEXTURE_DIR + source.get("source").asString + "/"
                            names.filter { it.startsWith(dir) && it.endsWith(".png") }.sorted()
                        }
                        "minecraft:single" -> {
                            val res = source.get("resource").asString.removePrefix("minecraft:")
                            listOf("$TEXTURE_DIR$res.png")
                        }
                        else -> {
                            // paletted_permutations and anything else: no single
                            // source PNG exists, so it can be neither measured
                            // nor referenced by a font provider.
                            skipped++
                            emptyList()
                        }
                    }

                    val stripDir = when (type) {
                        "minecraft:directory" -> TEXTURE_DIR + source.get("source").asString + "/"
                        else -> TEXTURE_DIR
                    }

                    for (path in texturePaths) {
                        val entry = zip.getEntry(path) ?: continue
                        val spriteName = prefix + path.removePrefix(stripDir).removeSuffix(".png")

                        val image = zip.getInputStream(entry).use { ImageIO.read(it) } ?: continue
                        var width = image.width
                        var height = image.height

                        val mcmetaEntry = zip.getEntry("$path.mcmeta")
                        var isAnimated = false
                        var slice: JsonObject? = null

                        if (mcmetaEntry != null) {
                            val meta = zip.getInputStream(mcmetaEntry).use {
                                JsonParser.parseString(it.reader().readText()).asJsonObject
                            }
                            meta.getAsJsonObject("animation")?.let { anim ->
                                isAnimated = true
                                // An animation strip's rendered frame is square
                                // unless it declares an explicit height.
                                height = anim.get("height")?.asInt ?: width
                            }
                            meta.getAsJsonObject("gui")
                                ?.getAsJsonObject("scaling")
                                ?.takeIf { it.get("type")?.asString == "nine_slice" }
                                ?.let { slice = parseBorder(it) }
                        }

                        if (isAnimated) animated++
                        if (slice != null) nineSlice++

                        val obj = JsonObject().apply {
                            addProperty("atlas", atlasName)
                            addProperty("sprite", spriteName)
                            addProperty("width", width)
                            addProperty("height", height)
                            addProperty("texture", "minecraft:" + path.removePrefix(TEXTURE_DIR))
                            if (isAnimated) addProperty("animated", true)
                            addProperty("greyscale", isGreyscale(image))
                            slice?.let { add("nineSlice", it) }
                        }
                        sprites.add(obj)
                    }
                }
            }
        }

        lastStats = Stats(sprites.size, animated, nineSlice, skipped)

        val root = JsonObject().apply {
            addProperty("sourceVersion", mcVersion)
            addProperty("generator", "SpriteIndexGenerator")
            add("sprites", com.google.gson.JsonArray().also { arr -> sprites.forEach(arr::add) })
        }
        return GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n"
    }

    /** `border` is either a single int or an object with per-side insets. */
    private fun parseBorder(scaling: JsonObject): JsonObject {
        val border = scaling.get("border")
        val out = JsonObject()
        if (border != null && border.isJsonObject) {
            val b = border.asJsonObject
            out.addProperty("left", b.get("left").asInt)
            out.addProperty("top", b.get("top").asInt)
            out.addProperty("right", b.get("right").asInt)
            out.addProperty("bottom", b.get("bottom").asInt)
        } else {
            val n = border?.asInt ?: 0
            out.addProperty("left", n)
            out.addProperty("top", n)
            out.addProperty("right", n)
            out.addProperty("bottom", n)
        }
        out.addProperty("stretchInner", scaling.get("stretch_inner")?.asBoolean ?: false)
        return out
    }

    /** True when every non-transparent pixel has r == g == b. */
    private fun isGreyscale(image: java.awt.image.BufferedImage): Boolean {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24) == 0) continue
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                if (r != g || g != b) return false
            }
        }
        return true
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 3) {
            "usage: SpriteIndexGenerator <clientJar> <mcVersion> <outputFile>"
        }
        val jar = Path.of(args[0])
        require(java.nio.file.Files.exists(jar)) { "client jar not found: $jar" }

        val json = generate(jar, args[1])
        val out = Path.of(args[2])
        java.nio.file.Files.createDirectories(out.parent)
        java.nio.file.Files.writeString(out, json)

        val s = lastStats
        println("Wrote $out")
        println("  sprites indexed : ${s.indexed}")
        println("  animated        : ${s.animated} (glyph-ineligible)")
        println("  glyph-eligible  : ${s.indexed - s.animated}")
        println("  nine-slice      : ${s.nineSlice}")
        println("  sources skipped : ${s.skippedSources} (paletted_permutations)")
    }
}
