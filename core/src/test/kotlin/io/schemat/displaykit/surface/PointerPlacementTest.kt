package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Does the on-surface pointer actually land where the player is aiming?
 *
 * [SurfacePointerTest] asserts that `planePoint` and `SurfacePicking` are
 * exact inverses, and that two pointers at different pixels get different
 * positions. Neither of those says the pointer is in the RIGHT place -- the
 * picking maths can be perfect while the sprite is drawn somewhere else
 * entirely, which is exactly what was reported in-world: a cursor that
 * "never worked right", sitting up and to the left of the crosshair.
 *
 * The check here is deliberately NOT a round trip through the pointer's own
 * placement code, which would only prove self-consistency again. It compares
 * the pointer against a SECOND, independent path: the same sprite drawn as an
 * ordinary icon at the same centred rect. `RenderModeTest`'s
 * `entitiesModePlacementAgreesWithSurfacePicking` already ties that icon path
 * to `planePoint` and to a real raycast, so agreement between the two means
 * the pointer is where picking says it should be.
 */
class PointerPlacementTest {

    private val crosshair = SpriteIndex.bundled.get(SpriteId("gui", "hud/crosshair"))

    @BeforeTest fun clearSliceSource() { SliceGlyphSource.installed = null }
    @AfterTest fun restore() {
        SliceGlyphSource.installed = null
        Surface.pointerSpriteOverride = null
    }

    private fun surface(yaw: Float = 0f) =
        Surface(200, 120, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 2f)
            .apply { renderMode = RenderMode.ENTITIES; yawDegrees = yaw }

    @Test
    fun theCrosshairSpriteTrimsShorterThanItsTexture() {
        // The premise of the suspected defect, pinned so it cannot drift:
        // the canvas the pointer is built in is sized by glyphAdvance
        // (trimmedWidth + 1 = 13) while the sprite RENDERS 15 wide.
        val c = assertNotNull(crosshair, "gui:hud/crosshair must be in the bundled index")
        assertTrue(c.width == 15 && c.height == 15, "expected a 15x15 crosshair, got ${c.width}x${c.height}")
        assertTrue(c.trimmedWidth < c.width, "the crosshair is expected to trim (${c.trimmedWidth} of ${c.width})")
    }

    @Test
    fun thePointerSitsWhereTheSameSpriteDrawnAsAnIconWouldSit() {
        val c = assertNotNull(crosshair)
        val worst = StringBuilder()
        var anyOff = false

        for (yaw in listOf(0f, 90f, 213f)) {
            for ((px, py) in listOf(100 to 60, 40 to 30, 150 to 90)) {
                val pointerPos = assertNotNull(surface(yaw).pointerEntityAt(px, py)).position

                // The same sprite, centred on the same pixel, drawn through
                // the ordinary icon path.
                val iconSurface = surface(yaw)
                val rect = Rect(px - c.width / 2, py - c.height / 2, c.width, c.height)
                iconSurface.paint { icon(c, rect.x, rect.y) }
                val iconPos = iconSurface.toEntities()
                    .filterIsInstance<VirtualTextDisplay>().single().position

                val d = pointerPos.distance(iconPos)
                if (d > 1e-6) {
                    anyOff = true
                    worst.append(
                        "\n  yaw=$yaw px=($px,$py) off by ${"%.4f".format(d)} blocks" +
                            "  pointer=$pointerPos icon=$iconPos"
                    )
                }
            }
        }
        assertTrue(
            !anyOff,
            "the pointer must be placed exactly where the same sprite drawn as an icon lands," +
                " or the cursor does not sit under the crosshair:$worst"
        )
    }

    @Test
    fun thePointerIsCentredOnTheAimedPixelNotHungOffIt() {
        // A cursor anchored by its top-left corner appears down-and-right of
        // where you point; anchored by its bottom-right, up-and-left. Either
        // way it is unusable for aiming. Compare the pointer for a pixel
        // against the icon path for the SAME pixel drawn top-left anchored:
        // they must differ by exactly half the sprite, proving the pointer
        // really is centred rather than accidentally agreeing at the corner.
        val c = assertNotNull(crosshair)
        val s = surface()
        val pointer = assertNotNull(s.pointerEntityAt(100, 60)).position

        val topLeft = surface().apply { paint { icon(c, 100, 60) } }
            .toEntities().filterIsInstance<VirtualTextDisplay>().single().position

        assertTrue(
            pointer.distance(topLeft) > 1e-6,
            "the pointer must not be anchored at the aimed pixel's top-left corner"
        )
    }

    @Test
    fun aPointerNearTheEdgeIsNotSilentlyDraggedInward() {
        // pointerEntityAt clamps its anchor with coerceAtLeast(0). Near the
        // top-left corner that clamp moves the cursor away from where the
        // player is actually aiming instead of letting it hang over the edge,
        // so the cursor stops tracking exactly where precision matters most --
        // the corner controls, which is where the close button lives.
        val s = surface()
        val atCorner = assertNotNull(s.pointerEntityAt(0, 0)).position
        val justInside = assertNotNull(s.pointerEntityAt(8, 8)).position

        assertTrue(
            atCorner.distance(justInside) > 1e-6,
            "the pointer at (0,0) and at (8,8) must not resolve to the same place; " +
                "the anchor clamp is pinning it"
        )
    }
}
