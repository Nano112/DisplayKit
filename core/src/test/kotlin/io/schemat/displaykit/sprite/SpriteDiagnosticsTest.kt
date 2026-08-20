package io.schemat.displaykit.sprite

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpriteDiagnosticsTest {

    @BeforeTest fun reset() = SpriteDiagnostics.reset()
    @AfterTest fun tearDown() = SpriteDiagnostics.reset()

    private fun entry(grey: Boolean) = SpriteEntry(
        id = SpriteId("gui", "test"),
        width = 8, height = 8,
        texture = "minecraft:gui/test.png",
        greyscale = grey
    )

    @Test
    fun warnOnceEmitsASingleWarningPerKey() {
        repeat(50) { SpriteDiagnostics.warnOnce("k", "boom") }
        assertEquals(1, SpriteDiagnostics.warnings().size)
    }

    @Test
    fun distinctKeysWarnIndependently() {
        SpriteDiagnostics.warnOnce("a", "one")
        SpriteDiagnostics.warnOnce("b", "two")
        assertEquals(2, SpriteDiagnostics.warnings().size)
    }

    @Test
    fun versionMismatchWarns() {
        val index = SpriteIndex.loadFrom("""{"sourceVersion":"1.21.9","sprites":[]}""")
        SpriteDiagnostics.checkVersion(index, "1.21.11")
        assertEquals(1, SpriteDiagnostics.warnings().size)
        assertTrue(SpriteDiagnostics.warnings()[0].contains("1.21.9"))
    }

    @Test
    fun matchingVersionIsSilent() {
        val index = SpriteIndex.loadFrom("""{"sourceVersion":"1.21.11","sprites":[]}""")
        SpriteDiagnostics.checkVersion(index, "1.21.11")
        assertEquals(0, SpriteDiagnostics.warnings().size)
    }

    @Test
    fun tintingANonGreyscaleSpriteWarnsBecauseTintIsMultiplicative() {
        SpriteDiagnostics.checkTintable(entry(grey = false))
        assertEquals(1, SpriteDiagnostics.warnings().size)
        assertTrue(SpriteDiagnostics.warnings()[0].contains("greyscale"))
    }

    @Test
    fun tintingAGreyscaleSpriteIsSilent() {
        SpriteDiagnostics.checkTintable(entry(grey = true))
        assertEquals(0, SpriteDiagnostics.warnings().size)
    }

    @Test
    fun repeatedTintWarningsForTheSameSpriteCollapseToOne() {
        repeat(30) { SpriteDiagnostics.checkTintable(entry(grey = false)) }
        assertEquals(1, SpriteDiagnostics.warnings().size)
    }
}
