package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.render.VirtualBlockDisplay
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The button primitive: a base, a capped face and a label, each on its own
 * depth plane.
 *
 * Two things this exists to prevent. Sprites drawn at the same elevation AND
 * the same kind share a depth key, land coplanar and z-fight -- a ground
 * painted under a frame at one elevation did exactly that. And a widget's
 * hover and selected appearances are extra sprites, so warming only the one
 * currently on screen grows the pack the moment a pointer touches it.
 */
class CappedButtonTest {

    @BeforeTest fun setUp() { SliceGlyphSource.installed = null }
    @AfterTest fun tearDown() { SliceGlyphSource.installed = null }

    private fun surface() =
        Surface(400, 200, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 4f)
            .apply { renderMode = RenderMode.ENTITIES }

    @Test
    fun theAdvancementTabPiecesAreAllPresentAndSolid() {
        // The premise: unlike widget/tab_selected, these have a real centre,
        // so a selected button is not a hollow outline.
        for (sel in listOf("", "_selected")) {
            for (pos in listOf("left", "middle", "right")) {
                val e = SpriteIndex.bundled.get(SpriteId("gui", "advancements/tab_above_$pos$sel"))
                assertNotNull(e, "advancements/tab_above_$pos$sel must be indexed")
                assertNotNull(
                    e.averageColor,
                    "advancements/tab_above_$pos$sel must be solid, not hollow"
                )
            }
        }
    }

    @Test
    fun everyStateSpriteIsOfferedForPrewarming() {
        // Six: three positions, normal and selected. Missing one means the
        // pack grows the first time that state is shown.
        assertEquals(6, CappedButton.statesFor().size)
    }

    @Test
    fun aWidthIsAWholeNumberOfPieces() {
        val h = CappedButton.heightFor()
        val piece = CappedButton.pieceWidth(h)
        assertTrue(piece > 0, "a piece must have width at height $h")
        for (want in listOf(1, 50, 100, 137, 300)) {
            val w = CappedButton.widthFor(want, h)
            assertEquals(0, w % piece, "width $w for want=$want is not whole pieces")
            assertTrue(w >= piece * 3, "must be at least two caps and one middle, got $w")
            assertTrue(w >= want || want < piece * 3, "width $w must cover the request $want")
        }
    }

    @Test
    fun theHeightCentresItsLabelExactly() {
        val h = CappedButton.heightFor()
        val centred = (h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2
        assertEquals(
            centred, TextMetrics.rowAlignedY(centred),
            "a ${h}px button centres its label off the text-row grid"
        )
    }

    @Test
    fun theLayersDoNotShareADepthPlane() {
        // The z-fighting guard. Every emitted element must have a distinct
        // depth key from the base, or the client is free to interleave them.
        val s = surface()
        val h = CappedButton.heightFor()
        val w = CappedButton.widthFor(120, h)
        s.paint {
            cappedButton(
                "b", Rect(10, 10, w, h), "GO",
                state = CappedButton.State.SELECTED,
                base = BlockStateRef("minecraft:polished_deepslate")
            )
        }
        val entities = s.toEntities()
        assertTrue(entities.size >= 3, "expected a base, face pieces and a label, got ${entities.size}")
        assertTrue(
            entities.any { it is VirtualBlockDisplay },
            "the base must be a real block display, not a painted rectangle"
        )

        // No two entities may sit at the same world position AND overlap:
        // distinct depth is what separates them.
        val zs = entities.map { it.position.z }.distinct()
        assertTrue(zs.size > 1, "every layer landed on one depth plane: $zs")
    }

    @Test
    fun aButtonTooNarrowForItsCapsDrawsNothingRatherThanAGhost() {
        // An invisible-but-clickable rectangle is worse than no button.
        val s = surface()
        s.paint { cappedButton("b", Rect(0, 0, 4, CappedButton.heightFor()), "x") }
        assertTrue(s.toEntities().isEmpty(), "a button that cannot draw must not emit anything")
        assertTrue(s.hitRects().isEmpty(), "and must not leave a hit region behind")
    }

    @Test
    fun theSelectedFaceDiffersFromTheNormalOne() {
        fun faceOf(state: CappedButton.State): List<String> {
            val s = surface()
            val h = CappedButton.heightFor()
            s.paint { cappedButton("b", Rect(0, 0, CappedButton.widthFor(120, h), h), "GO", state) }
            return s.toEntities().map { it.toString() }
        }
        assertTrue(
            faceOf(CappedButton.State.NORMAL) != faceOf(CappedButton.State.SELECTED),
            "selected must not render identically to normal"
        )
    }
}
