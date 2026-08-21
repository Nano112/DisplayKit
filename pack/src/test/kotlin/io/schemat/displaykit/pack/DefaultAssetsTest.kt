package io.schemat.displaykit.pack

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the debug stub that once painted every text-display
 * background in the game pure red: `rendertype_text_background.fsh` is a
 * VANILLA core shader override, so a hardcoded color there leaks into every
 * consumer of text-display backgrounds (DisplayKit's own UI, hardwired,
 * blockbrains), not just DisplayKit's.
 *
 * This is a crude test -- it greps shader source rather than rendering
 * anything -- but it would have caught the original bug, and that kind of
 * regression is easy to reintroduce silently while iterating on the
 * corner-radius effect this shader exists for.
 */
class DefaultAssetsTest {

    private fun textBackgroundFsh(): String {
        val b = PackBuilder(PackConfig())
        DefaultAssets.contributeAssets(b)
        return b.capturedJson("assets/minecraft/shaders/core/rendertype_text_background.fsh")
    }

    @Test
    fun textBackgroundFshReadsVertexColorInsteadOfAHardcodedConstant() {
        val fsh = textBackgroundFsh()
        assertTrue(
            fsh.contains("vertexColor"),
            "the fragment shader must read the interpolated vertex color, not paint a fixed color"
        )
    }

    @Test
    fun textBackgroundFshDoesNotHardcodeDebugRed() {
        val fsh = textBackgroundFsh()
        assertFalse(
            fsh.contains("vec4(1.0, 0.0, 0.0, 1.0)"),
            "must not regress to the debug stub that painted every text background red"
        )
    }
}
