package io.schemat.displaykit.pack.gen

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteIndex
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * Crops every nine-sliced sprite into its regions at BUILD time.
 *
 * This exists because a dedicated server has no client jar, so it can never
 * produce these crops itself — and no uniform font-provider grid can express a
 * 3/194/3 split, so referencing the vanilla texture is not an option either.
 * These are the only image bytes DisplayKit ships.
 *
 * Output is committed to the pack module's resources.
 */
object SpriteSliceGenerator {

    data class Stats(val sprites: Int, val crops: Int, val skippedDegenerate: Int)

    /** Filesystem-safe resource name for one crop. */
    fun cropName(entry: SpriteEntry, x: Int, y: Int): String =
        "${entry.id.atlas}__${entry.id.sprite.replace('/', '_')}__${x}_$y.png"

    fun generate(clientJar: Path, indexJson: String, outDir: Path): Stats {
        val index = SpriteIndex.loadFrom(indexJson)
        Files.createDirectories(outDir)

        var sprites = 0; var crops = 0; var skipped = 0
        val manifestSprites = JsonArray()

        ZipFile(clientJar.toFile()).use { zip ->
            // sorted for deterministic output
            for (entry in index.all().sortedBy { it.id.toString() }) {
                val s = entry.nineSlice ?: continue
                val path = "assets/minecraft/textures/" + entry.texture.removePrefix("minecraft:")
                val ze = zip.getEntry(path) ?: continue
                val img = zip.getInputStream(ze).use { ImageIO.read(it) } ?: continue

                val xs = listOf(0 to s.left, s.left to (entry.width - s.left - s.right),
                                (entry.width - s.right) to s.right)
                val ys = listOf(0 to s.top, s.top to (entry.height - s.top - s.bottom),
                                (entry.height - s.bottom) to s.bottom)

                val cropArr = JsonArray()
                for ((oy, h) in ys) for ((ox, w) in xs) {
                    if (w <= 0 || h <= 0) { skipped++; continue }
                    val sub = img.getSubimage(ox, oy, w, h)
                    val name = cropName(entry, ox, oy)
                    ImageIO.write(sub, "png", outDir.resolve(name).toFile())
                    cropArr.add(JsonObject().apply {
                        addProperty("x", ox); addProperty("y", oy)
                        addProperty("w", w); addProperty("h", h)
                        addProperty("file", name)
                    })
                    crops++
                }

                manifestSprites.add(JsonObject().apply {
                    addProperty("atlas", entry.id.atlas)
                    addProperty("sprite", entry.id.sprite)
                    addProperty("width", entry.width)
                    addProperty("height", entry.height)
                    add("border", JsonObject().apply {
                        addProperty("left", s.left); addProperty("top", s.top)
                        addProperty("right", s.right); addProperty("bottom", s.bottom)
                        addProperty("stretchInner", s.stretchInner)
                    })
                    add("crops", cropArr)
                })
                sprites++
            }
        }

        val manifest = JsonObject().apply {
            addProperty("sourceVersion", index.sourceVersion)
            add("sprites", manifestSprites)
        }
        Files.writeString(outDir.resolve("manifest.json"),
            GsonBuilder().setPrettyPrinting().create().toJson(manifest) + "\n")

        return Stats(sprites, crops, skipped)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 3) { "usage: SpriteSliceGenerator <clientJar> <indexJson> <outDir>" }
        val jar = Path.of(args[0])
        require(Files.exists(jar)) { "client jar not found: $jar" }
        val stats = generate(jar, Files.readString(Path.of(args[1])), Path.of(args[2]))
        println("Wrote slices to ${args[2]}")
        println("  nine-slice sprites : ${stats.sprites}")
        println("  crops written      : ${stats.crops}")
        println("  degenerate skipped : ${stats.skippedDegenerate}")
    }
}
