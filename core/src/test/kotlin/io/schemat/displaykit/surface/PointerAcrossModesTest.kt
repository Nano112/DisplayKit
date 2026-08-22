package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cursor must land on the aimed pixel in BOTH render modes.
 *
 * [PointerPlacementTest] pinned the entity path. This pins the composited
 * one, which is what a player with the resource pack actually sees -- and it
 * is a different code path: `pointerEntityAt` builds a `SpriteCanvas` under
 * COMPOSITED and delegates to `spriteEntity` under ENTITIES.
 *
 * The entity path is now measured-correct against the backing slab (see
 * `CalibrationWindow`; the sprite anchoring fix took it from h/8 canvas
 * pixels high to zero). So it is a legitimate reference: for the same aimed
 * pixel, the composited cursor must resolve to the same world point.
 */
class PointerAcrossModesTest {

    private val crosshair = SpriteIndex.bundled.get(SpriteId("gui", "hud/crosshair"))

    private object FakeSlices : SliceGlyphSource {
        override fun request(id: SpriteId) {}
        override fun codepointFor(id: SpriteId, srcX: Int, srcY: Int, ascent: Int) = 0xF8000
        override fun advanceFor(id: SpriteId, srcX: Int, srcY: Int): Int? = null
    }

    @BeforeTest fun setUp() { SliceGlyphSource.installed = null }
    @AfterTest fun tearDown() {
        SliceGlyphSource.installed = null
        Surface.pointerSpriteOverride = null
    }

    private fun surface(mode: RenderMode, yaw: Float = 0f) =
        Surface(200, 120, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 2f).apply {
            renderMode = mode
            yawDegrees = yaw
        }

    @Test
    fun theCursorResolvesToTheSameWorldPointInBothModes() {
        assertNotNull(crosshair, "gui:hud/crosshair must be in the bundled index")
        val report = StringBuilder()
        var worst = 0.0

        for (yaw in listOf(0f, 90f, 213f)) {
            for ((px, py) in listOf(100 to 60, 30 to 20, 170 to 100)) {
                SliceGlyphSource.installed = null
                val ent = assertNotNull(surface(RenderMode.ENTITIES, yaw).pointerEntityAt(px, py))

                SliceGlyphSource.installed = FakeSlices
                val com = assertNotNull(surface(RenderMode.COMPOSITED, yaw).pointerEntityAt(px, py))

                val d = ent.position.distance(com.position)
                if (d > worst) worst = d
                if (d > 1e-6) {
                    report.append("\n  yaw=$yaw px=($px,$py) apart by ${"%.4f".format(d)} blocks")
                    report.append("\n     entities=${ent.position}\n     composited=${com.position}")
                }
            }
        }
        // 0.01 blocks is one canvas pixel on this surface (2 blocks / 200 px).
        assertTrue(
            worst < 1e-6,
            "the cursor must sit at the same world point whichever way the surface renders, " +
                "or it tracks the crosshair in one mode and not the other " +
                "(worst ${"%.4f".format(worst)} blocks = ${"%.2f".format(worst / 0.01)} canvas px):$report"
        )
    }
}
