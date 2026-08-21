package io.schemat.displaykit.pack.gen

import com.google.gson.JsonParser
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpriteSliceGeneratorTest {

    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        for (p in temps.reversed()) {
            if (Files.isDirectory(p)) Files.walk(p).sorted(Comparator.reverseOrder()).forEach(Files::delete)
            else Files.deleteIfExists(p)
        }
    }

    private fun png(w: Int, h: Int, c: Color): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics(); g.color = c; g.fillRect(0, 0, w, h); g.dispose()
        val out = ByteArrayOutputStream(); ImageIO.write(img, "png", out); return out.toByteArray()
    }

    private fun jar(): Path {
        val p = Files.createTempFile("slice-gen", ".jar").also { temps.add(it) }
        ZipOutputStream(Files.newOutputStream(p)).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("assets/minecraft/textures/gui/sprites/widget/button.png", png(200, 20, Color.BLUE))
            put("assets/minecraft/textures/gui/sprites/widget/tab.png", png(130, 24, Color.GREEN))
            put("assets/minecraft/textures/gui/sprites/hud/hotbar.png", png(182, 22, Color.RED))
        }
        return p
    }

    private val index = """
        {"sourceVersion":"1.21.11","sprites":[
          {"atlas":"gui","sprite":"widget/button","width":200,"height":20,
           "texture":"minecraft:gui/sprites/widget/button.png",
           "nineSlice":{"left":3,"top":3,"right":3,"bottom":3,"stretchInner":false}},
          {"atlas":"gui","sprite":"widget/tab","width":130,"height":24,
           "texture":"minecraft:gui/sprites/widget/tab.png",
           "nineSlice":{"left":2,"top":2,"right":2,"bottom":0,"stretchInner":false}},
          {"atlas":"gui","sprite":"hud/hotbar","width":182,"height":22,
           "texture":"minecraft:gui/sprites/hud/hotbar.png"}
        ]}
    """.trimIndent()

    // Same three sprites as [index], listed in a different order. Used to
    // prove the generator's explicit sort — not incidental map/JSON order —
    // is what makes output deterministic.
    private val indexReordered = """
        {"sourceVersion":"1.21.11","sprites":[
          {"atlas":"gui","sprite":"hud/hotbar","width":182,"height":22,
           "texture":"minecraft:gui/sprites/hud/hotbar.png"},
          {"atlas":"gui","sprite":"widget/tab","width":130,"height":24,
           "texture":"minecraft:gui/sprites/widget/tab.png",
           "nineSlice":{"left":2,"top":2,"right":2,"bottom":0,"stretchInner":false}},
          {"atlas":"gui","sprite":"widget/button","width":200,"height":20,
           "texture":"minecraft:gui/sprites/widget/button.png",
           "nineSlice":{"left":3,"top":3,"right":3,"bottom":3,"stretchInner":false}}
        ]}
    """.trimIndent()

    // Two distinct sprite ids whose `/`-to-`_` normalisation collides:
    // "a/b_c" and "a_b/c" both become "a_b_c".
    private fun collidingJar(): Path {
        val p = Files.createTempFile("slice-gen-collision", ".jar").also { temps.add(it) }
        ZipOutputStream(Files.newOutputStream(p)).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("assets/minecraft/textures/gui/sprites/a/b_c.png", png(10, 10, Color.BLUE))
            put("assets/minecraft/textures/gui/sprites/a_b/c.png", png(10, 10, Color.GREEN))
        }
        return p
    }

    private val collidingIndex = """
        {"sourceVersion":"1.21.11","sprites":[
          {"atlas":"gui","sprite":"a/b_c","width":10,"height":10,
           "texture":"minecraft:gui/sprites/a/b_c.png",
           "nineSlice":{"left":1,"top":1,"right":1,"bottom":1,"stretchInner":false}},
          {"atlas":"gui","sprite":"a_b/c","width":10,"height":10,
           "texture":"minecraft:gui/sprites/a_b/c.png",
           "nineSlice":{"left":1,"top":1,"right":1,"bottom":1,"stretchInner":false}}
        ]}
    """.trimIndent()

    private fun run(): Pair<Path, SpriteSliceGenerator.Stats> {
        val out = Files.createTempDirectory("slices").also { temps.add(it) }
        val stats = SpriteSliceGenerator.generate(jar(), index, out)
        return out to stats
    }

    @Test
    fun onlyNineSliceSpritesAreProcessed() {
        val (_, stats) = run()
        assertEquals(2, stats.sprites, "hotbar has no nine-slice metadata and must be skipped")
    }

    @Test
    fun degenerateRegionsAreSkippedAndCounted() {
        val (_, stats) = run()
        // button contributes 9; tab has bottom=0 so contributes 6
        assertEquals(15, stats.crops)
        assertEquals(3, stats.skippedDegenerate)
    }

    @Test
    fun everyManifestCropResolvesToAWrittenFile() {
        val (out, _) = run()
        val m = JsonParser.parseString(Files.readString(out.resolve("manifest.json"))).asJsonObject
        var checked = 0
        for (s in m.getAsJsonArray("sprites")) {
            for (c in s.asJsonObject.getAsJsonArray("crops")) {
                val f = c.asJsonObject.get("file").asString
                assertTrue(Files.exists(out.resolve(f)), "missing crop file $f")
                checked++
            }
        }
        assertEquals(15, checked)
    }

    @Test
    fun cropDimensionsMatchTheDeclaredBorders() {
        val (out, _) = run()
        val m = JsonParser.parseString(Files.readString(out.resolve("manifest.json"))).asJsonObject
        val button = m.getAsJsonArray("sprites").map { it.asJsonObject }
            .first { it.get("sprite").asString == "widget/button" }
        val crops = button.getAsJsonArray("crops").map { it.asJsonObject }
        val topLeft = crops.first { it.get("x").asInt == 0 && it.get("y").asInt == 0 }
        assertEquals(3, topLeft.get("w").asInt)
        assertEquals(3, topLeft.get("h").asInt)
        val centre = crops.first { it.get("x").asInt == 3 && it.get("y").asInt == 3 }
        assertEquals(194, centre.get("w").asInt)
        assertEquals(14, centre.get("h").asInt)
        // and the written PNG really is that size
        val img = ImageIO.read(out.resolve(centre.get("file").asString).toFile())
        assertEquals(194, img.width); assertEquals(14, img.height)
    }

    @Test
    fun generationIsDeterministic() {
        val out1 = Files.createTempDirectory("s1").also { temps.add(it) }
        val out2 = Files.createTempDirectory("s2").also { temps.add(it) }
        val j = jar()
        // Same sprites, different declaration order — this only passes if the
        // generator's own sort (not incidental input order) drives output
        // order. Without that sort, this would fail even though a same-input
        // rerun would still trivially match.
        SpriteSliceGenerator.generate(j, index, out1)
        SpriteSliceGenerator.generate(j, indexReordered, out2)
        val a = Files.readString(out1.resolve("manifest.json"))
        val b = Files.readString(out2.resolve("manifest.json"))
        assertEquals(a, b, "manifest must be byte-identical regardless of input sprite order")
    }

    @Test
    fun collidingCropFilenamesThrowNamingBothSprites() {
        val out = Files.createTempDirectory("collide").also { temps.add(it) }
        val ex = assertFailsWith<IllegalStateException> {
            SpriteSliceGenerator.generate(collidingJar(), collidingIndex, out)
        }
        assertTrue(ex.message?.contains("a/b_c") == true,
            "message should name the first sprite id: ${ex.message}")
        assertTrue(ex.message?.contains("a_b/c") == true,
            "message should name the second sprite id: ${ex.message}")
    }
}
