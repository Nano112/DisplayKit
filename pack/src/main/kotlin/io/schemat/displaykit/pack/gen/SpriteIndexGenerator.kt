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
                            // An animated strip is never a glyph, so its
                            // advance is meaningless -- and measuring it would
                            // scan the whole strip rather than one frame.
                            addProperty(
                                "trimmedWidth",
                                if (isAnimated) width else actualGlyphWidth(image)
                            )
                            // Omitted entirely when there is nothing to
                            // measure: an animated strip (averaging one would
                            // blend every frame together) or a region with no
                            // opaque pixels. An absent field parses back to
                            // null, which tells the ENTITIES renderer to
                            // substitute no fill at all -- distinct from any
                            // colour it could have named. See
                            // SpriteEntry.averageColor.
                            val avg = if (isAnimated) null else averageColor(image, slice)
                            if (avg != null) addProperty("averageColor", avg)
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
            out.addProperty("left", b.get("left")?.asInt ?: 0)
            out.addProperty("top", b.get("top")?.asInt ?: 0)
            out.addProperty("right", b.get("right")?.asInt ?: 0)
            out.addProperty("bottom", b.get("bottom")?.asInt ?: 0)
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

    /** Alpha at or below this is treated as transparent halo, not real pixel colour. */
    private const val ALPHA_THRESHOLD = 16

    /**
     * Mean RGB of this sprite's own pixels, packed `0xRRGGBB`, for
     * [SpriteEntry.averageColor].
     *
     * [RenderMode.ENTITIES] substitutes a flat fill for the part of a
     * nine-slice frame it cannot crop -- see `Surface.recordFrame` -- and that
     * substitute is exactly the CENTRE region of the source texture, inside
     * its declared borders. So when [border] is present only that region is
     * sampled; a sprite with no nine-slice metadata (a plain icon, a solid
     * fill) samples its whole image instead, since nothing about it is ever
     * replaced by a flat fill.
     *
     * Pixels at or below [ALPHA_THRESHOLD] alpha are skipped -- a
     * near-transparent anti-aliased edge would otherwise pull the mean toward
     * black regardless of the sprite's real colour.
     *
     * Returns null when nothing qualifies, i.e. the measured region is fully
     * transparent. That is a real answer, not a missing one: it says the
     * sprite draws nothing there, so the renderer must substitute nothing.
     * Reporting white instead made a hollow frame look like a white one --
     * `gui/widget/tab_selected` rendered as an opaque white box.
     */
    internal fun averageColor(image: java.awt.image.BufferedImage, border: JsonObject?): Int? {
        val width = image.width
        val height = image.height
        val left = (border?.get("left")?.asInt ?: 0).coerceIn(0, width)
        val top = (border?.get("top")?.asInt ?: 0).coerceIn(0, height)
        val right = (border?.get("right")?.asInt ?: 0).coerceIn(0, width)
        val bottom = (border?.get("bottom")?.asInt ?: 0).coerceIn(0, height)
        val x0 = left
        val x1 = maxOf(x0, width - right)
        val y0 = top
        val y1 = maxOf(y0, height - bottom)

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0L
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val argb = image.getRGB(x, y)
                if (((argb ushr 24) and 0xFF) <= ALPHA_THRESHOLD) continue
                rSum += (argb shr 16) and 0xFF
                gSum += (argb shr 8) and 0xFF
                bSum += argb and 0xFF
                count++
            }
        }
        if (count == 0L) return null
        return (((rSum / count).toInt()) shl 16) or
            (((gSum / count).toInt()) shl 8) or
            ((bSum / count).toInt())
    }

    /**
     * The width the client will measure this texture at when it becomes a
     * bitmap glyph — the rightmost non-empty column, plus one.
     *
     * A faithful port of `BitmapProvider$Definition.getActualGlyphWidth` in
     * the 1.21.11 client, which scans columns right-to-left and returns as
     * soon as one contains a pixel whose `getLuminanceOrAlpha` is non-zero.
     * A fully empty texture yields 0.
     *
     * `NativeImage.getLuminanceOrAlpha` returns the ALPHA channel when the
     * format has one and the LUMINANCE otherwise, so an opaque RGB texture
     * has its black columns trimmed just like a transparent one — reproduced
     * here via [BufferedImage.getColorModel]'s alpha support.
     *
     * Getting this wrong under-advances the text cursor by a per-sprite
     * amount that accumulates across a row. See
     * `docs/superpowers/specs/2026-08-21-text-display-layout-truth.md`.
     */
    internal fun actualGlyphWidth(image: java.awt.image.BufferedImage): Int {
        val hasAlpha = image.colorModel.hasAlpha()
        for (x in image.width - 1 downTo 0) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                val sample = if (hasAlpha) {
                    (argb ushr 24) and 0xFF
                } else {
                    // NativeImage's luminance is the low byte of the colour in
                    // its single-channel formats; for an RGB source every
                    // channel is equal for greys, and any non-black pixel
                    // terminates the scan regardless of which we sample.
                    ((argb shr 16) and 0xFF) or ((argb shr 8) and 0xFF) or (argb and 0xFF)
                }
                if (sample != 0) return x + 1
            }
        }
        return 0
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
