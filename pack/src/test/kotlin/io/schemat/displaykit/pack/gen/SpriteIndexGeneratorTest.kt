package io.schemat.displaykit.pack.gen

import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpriteIndexGeneratorTest {

    private lateinit var jar: Path

    private fun png(w: Int, h: Int, color: Color): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, w, h)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun buildJar(): Path {
        val path = Files.createTempFile("sprite-index-test", ".jar")
        ZipOutputStream(Files.newOutputStream(path)).use { zos ->
            fun put(name: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }

            put("assets/minecraft/atlases/gui.json", """
                {"sources":[
                  {"type":"minecraft:directory","source":"gui/sprites","prefix":""},
                  {"type":"minecraft:single","resource":"minecraft:lone/thing"}
                ]}
            """.trimIndent().toByteArray())

            put("assets/minecraft/atlases/armor_trims.json", """
                {"sources":[
                  {"type":"minecraft:paletted_permutations",
                   "textures":["trims/items/boots_trim"],
                   "palette_key":"trims/color_palettes/trim_palette",
                   "permutations":{"quartz":"trims/color_palettes/quartz"}}
                ]}
            """.trimIndent().toByteArray())

            // Plain non-square sprite
            put("assets/minecraft/textures/gui/sprites/hud/hotbar.png", png(182, 22, Color.RED))

            // Nine-slice sprite
            put("assets/minecraft/textures/gui/sprites/widget/button.png", png(200, 20, Color.BLUE))
            put("assets/minecraft/textures/gui/sprites/widget/button.png.mcmeta",
                """{"gui":{"scaling":{"type":"nine_slice","width":200,"height":20,"border":3}}}"""
                    .toByteArray())

            // Animated sprite: 16 wide, 4 frames tall, declared height 16
            put("assets/minecraft/textures/gui/sprites/anim/flame.png", png(16, 64, Color.ORANGE))
            put("assets/minecraft/textures/gui/sprites/anim/flame.png.mcmeta",
                """{"animation":{"frametime":2}}""".toByteArray())

            // Greyscale sprite (tintable)
            put("assets/minecraft/textures/gui/sprites/mask/white.png", png(8, 8, Color.WHITE))

            // minecraft:single source target
            put("assets/minecraft/textures/lone/thing.png", png(32, 32, Color.GREEN))

            // Nine-slice sprite with an object-form border that omits a side
            // (missing sides default to 0, per the int-form border's convention).
            put("assets/minecraft/textures/gui/sprites/widget/panel.png", png(100, 40, Color.MAGENTA))
            put("assets/minecraft/textures/gui/sprites/widget/panel.png.mcmeta",
                """{"gui":{"scaling":{"type":"nine_slice","width":100,"height":40,
                    "border":{"top":5,"right":5,"bottom":5}}}}""".toByteArray())

            // Animated sprite with an explicit animation.height that must win
            // over the width-derived square-frame default.
            put("assets/minecraft/textures/gui/sprites/anim/campfire.png", png(16, 128, Color.CYAN))
            put("assets/minecraft/textures/gui/sprites/anim/campfire.png.mcmeta",
                """{"animation":{"frametime":2,"height":32}}""".toByteArray())
        }
        return path
    }

    @AfterTest
    fun cleanup() {
        if (::jar.isInitialized) Files.deleteIfExists(jar)
    }

    private fun index(): SpriteIndex {
        jar = buildJar()
        return SpriteIndex.loadFrom(SpriteIndexGenerator.generate(jar, "1.21.11"))
    }

    @Test
    fun recordsTrueDimensionsAndTexturePath() {
        val hotbar = index().get(SpriteId("gui", "hud/hotbar"))!!
        assertEquals(182, hotbar.width)
        assertEquals(22, hotbar.height)
        assertEquals("minecraft:gui/sprites/hud/hotbar.png", hotbar.texture)
    }

    @Test
    fun harvestsNineSliceInsetsFromMcmeta() {
        val slice = index().get(SpriteId("gui", "widget/button"))!!.nineSlice!!
        assertEquals(3, slice.left)
        assertEquals(3, slice.top)
        assertEquals(3, slice.right)
        assertEquals(3, slice.bottom)
    }

    @Test
    fun animationCorrectsHeightAndMarksIneligible() {
        // A 16x64 strip with an animation block and no explicit height is a
        // square-framed animation: the rendered height is the width.
        val flame = index().get(SpriteId("gui", "anim/flame"))!!
        assertEquals(16, flame.width)
        assertEquals(16, flame.height)
        assertTrue(flame.animated)
        assertFalse(flame.glyphEligible)
    }

    @Test
    fun detectsGreyscaleForTintEligibility() {
        val i = index()
        assertTrue(i.get(SpriteId("gui", "mask/white"))!!.greyscale)
        assertFalse(i.get(SpriteId("gui", "hud/hotbar"))!!.greyscale)
    }

    @Test
    fun resolvesMinecraftSingleSources() {
        val lone = index().get(SpriteId("gui", "lone/thing"))!!
        assertEquals(32, lone.width)
        assertEquals("minecraft:lone/thing.png", lone.texture)
    }

    @Test
    fun skipsPalettedPermutationSources() {
        // armor_trims uses only paletted_permutations, so it contributes nothing.
        assertTrue(index().all().none { it.id.atlas == "armor_trims" })
        assertNull(index().get(SpriteId("armor_trims", "trims/items/boots_trim")))
    }

    @Test
    fun recordsSourceVersion() {
        assertEquals("1.21.11", index().sourceVersion)
    }

    @Test
    fun nineSliceBorderObjectDefaultsMissingSideToZero() {
        val slice = index().get(SpriteId("gui", "widget/panel"))!!.nineSlice!!
        assertEquals(0, slice.left)
        assertEquals(5, slice.top)
        assertEquals(5, slice.right)
        assertEquals(5, slice.bottom)
    }

    @Test
    fun animationExplicitHeightOverridesWidthDerivedDefault() {
        val campfire = index().get(SpriteId("gui", "anim/campfire"))!!
        assertEquals(16, campfire.width)
        assertEquals(32, campfire.height)
        assertTrue(campfire.animated)
    }
}
