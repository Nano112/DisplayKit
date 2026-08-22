package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the three client-layout facts that four rounds of visual bugs traced
 * back to. Each of these was, at some point, assumed rather than measured.
 *
 * See `docs/superpowers/specs/2026-08-21-text-display-layout-truth.md`.
 */
class TextDisplayLayoutTruthTest {

    private class NoSlices : SliceGlyphSource {
        override fun request(id: SpriteId) {}
        override fun codepointFor(id: SpriteId, srcX: Int, srcY: Int, ascent: Int) = 1
        override fun advanceFor(id: SpriteId, srcX: Int, srcY: Int): Int? = null
    }

    // entityOrigin()'s compensation only applies on the non-degraded path.
    @BeforeTest fun install() { SliceGlyphSource.installed = NoSlices() }
    @AfterTest fun clear() { SliceGlyphSource.installed = null }

    private fun surface(w: Int = 320, h: Int = 200) =
        Surface(w, h, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 4f)

    // --- Fact 1: the advance is the TRIMMED width plus one ---

    @Test
    fun glyphAdvanceComesFromTheTrimmedWidthNotTheDeclaredOne() {
        val padded = SpriteEntry(
            id = SpriteId("gui", "padded"),
            width = 16, height = 16, texture = "minecraft:padded.png",
            trimmedWidth = 14
        )
        assertEquals(15, padded.glyphAdvance, "advance is trimmedWidth + 1")
        assertEquals(17, padded.copy(trimmedWidth = 16).glyphAdvance)
    }

    @Test
    fun trimmedWidthDefaultsToTheDeclaredWidthWhenUnmeasured() {
        val e = SpriteEntry(
            id = SpriteId("gui", "plain"),
            width = 12, height = 12, texture = "minecraft:plain.png"
        )
        assertEquals(12, e.trimmedWidth)
        assertEquals(13, e.glyphAdvance)
    }

    // --- Fact 2: the renderer's line pitch is 10, not Font.lineHeight (9) ---

    @Test
    fun theRendererLinePitchIsTen() {
        assertEquals(
            10, TextMetrics.FONT_LINE_HEIGHT_PX,
            "DisplayRenderer\$TextDisplayRenderer computes 9 + 1; using Font's " +
                "own lineHeight of 9 drifts one pixel per row"
        )
    }

    // --- Fact 3: position is the canvas top-left, compensating for centring ---

    @Test
    fun everyCanvasCornerRoundTripsThroughPicking() {
        // If entityOrigin() and SurfacePicking disagree about where the canvas
        // sits, a window renders in one place and takes clicks in another.
        // Walking real corners through the ray maths catches that.
        val s = surface()
        s.paint { label("round trip", 0, 0, DkColor.WHITE) }

        val unit = (s.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        val straightAhead = Vec3d(0.0, 0.0, 1.0)
        for ((px, py) in listOf(0 to 0, 1 to 1, 100 to 50, s.widthPx - 1 to s.heightPx - 1)) {
            // Centre of pixel (px, py), per SurfacePicking's own mapping.
            val world = Vec3d(
                s.position.x + (px + 0.5) * unit,
                s.position.y - (py + 0.5) * unit,
                s.position.z - 2.0
            )
            val got = SurfacePicking.localPixel(s, world, straightAhead)
            assertNotNull(got, "pixel ($px,$py) should be pickable")
            assertEquals(px to py, got, "pixel ($px,$py) round trip")
        }
    }

    @Test
    fun theEntitySitsAtTheBlockCentreNotTheCanvasTopLeft() {
        val s = surface()
        s.paint { label("x", 0, 0, DkColor.WHITE) }
        val origin = s.entityOrigin()

        // The client centres the block horizontally on the entity and hangs it
        // upward from it, so the entity must sit right of, and below, the
        // canvas top-left that `position` names.
        assertTrue(origin.x > s.position.x, "entity is right of the canvas origin, got $origin")
        assertTrue(origin.y < s.position.y, "entity is below the canvas origin, got $origin")
    }

    @Test
    fun anchoringFixesTheBlockWidthSoContentCannotShiftTheSurface() {
        // A narrow widget and a wide one must produce the same block width, or
        // the surface slides sideways whenever its content changes.
        val a = surface().apply { paint { label("i", 0, 0, DkColor.WHITE) } }
        val b = surface().apply { paint { label("a much longer label", 0, 0, DkColor.WHITE) } }
        assertEquals(a.entityOrigin(), b.entityOrigin())
    }
    // --- Fact 4: the readable side is +Z, because of the built-in rotateY(PI) ---

    @Test
    fun yawFacingTurnsTheReadableSideTowardTheViewer() {
        // A viewer looking along +Z must end up on the surface's +Z side, so
        // the surface is turned a half-turn from the naive atan2 angle.
        assertEquals(180f, Surface.yawFacing(Vec3d(0.0, 0.0, 1.0)))
        // ...and a viewer looking along +X gets 90 + 180.
        assertEquals(270f, Surface.yawFacing(Vec3d(1.0, 0.0, 0.0)))
    }

    @Test
    fun theBackingSlabSitsOnTheFarSideOfTheReadableFace() {
        // Readable side is +Z, so the panel must be at NEGATIVE local z. When
        // this was +Z the slab parked between the viewer and the glyphs and
        // the whole UI vanished behind a blank rectangle.
        val s = surface()
        s.backingBlock = BlockStateRef.BLACK_CONCRETE
        s.paint { label("x", 0, 0, DkColor.WHITE) }
        val m = s.toBackingEntity()!!.transformation.joml

        // Where the slab's front-most corner (unit cube z=1) actually lands.
        val corner = m.transformPosition(org.joml.Vector3f(0f, 0f, 1f))
        assertTrue(
            corner.z() <= 1e-4f,
            "backing slab must not cross into the readable half-space, got z=" + corner.z()
        )
    }

    // --- Fact 5: the backing slab must track the TEXT BLOCK, not the canvas ---

    @Test
    fun theBackingSlabAlignsWithTheTextBlockAtEveryYaw() {
        // The slab used to hang off `position` (the canvas top-left) while the
        // text hangs off `entityOrigin` (the block centre) -- 1.5 blocks apart
        // on a 3-block window. At yaw 0 they coincidentally overlap; rotate
        // the window and the two pivot about different points, flinging the
        // panel away from its own UI. Corner-checking at several yaws is what
        // catches that; a single unrotated case does not.
        for (yaw in listOf(0f, 90f, 137f, 180f, 271f)) {
            val s = surface(346, 264)
            s.yawDegrees = yaw
            s.backingBlock = BlockStateRef.BLACK_CONCRETE
            s.paint { label("align", 0, 0, DkColor.WHITE) }

            val unit = (s.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
            val blockW = s.blockWidthPx()
            val blockH = s.blockHeightPx()
            val e = s.entityOrigin()
            val th = Math.toRadians(yaw.toDouble())
            val cs = Math.cos(th)
            val sn = Math.sin(th)

            val back = s.toBackingEntity()!!
            val m = back.transformation.joml
            val corners = listOf(
                Triple(0, 0, 0f to 1f),
                Triple(blockW, 0, 1f to 1f),
                Triple(0, blockH, 0f to 0f),
                Triple(blockW, blockH, 1f to 0f)
            )
            for ((px, py, sc) in corners) {
                val bx = e.x + unit * (px + 1 - blockW / 2.0) * cs
                val by = e.y + unit * (blockH - py)
                val bz = e.z - unit * (px + 1 - blockW / 2.0) * sn
                val v = m.transformPosition(org.joml.Vector3f(sc.first, sc.second, 0f))
                val gap = Math.sqrt(
                    Math.pow(bx - (back.position.x + v.x()), 2.0) +
                        Math.pow(by - (back.position.y + v.y()), 2.0) +
                        Math.pow(bz - (back.position.z + v.z()), 2.0)
                )
                // The only separation allowed is the deliberate depth step.
                val expected = (Surface.LAYER_Z_STEP + s.backingThicknessBlocks).toDouble()
                assertTrue(
                    Math.abs(gap - expected) < 1e-4,
                    "yaw $yaw corner ($px,$py): slab is $gap from the block, expected $expected"
                )
            }
        }
    }

    @Test
    fun theBackingSlabAlignsWithTheCanvasBoundsInEntitiesMode() {
        // Under RenderMode.ENTITIES nothing is ever painted into the shared
        // canvas -- each element becomes its own entity -- so the canvas's
        // emittedRowCount() stays 0, blockHeightPx() computes 0*10-1 = -1,
        // and entityOrigin() early-returns `position` unmodified because the
        // canvas's itemCount() is 0. The old text-block-derived slab sizing
        // produced a degenerate, detached panel from that. The backing must
        // instead track widthPx/heightPx and anchor at `position` (the
        // canvas top-left) directly.
        for (yaw in listOf(0f, 90f, 137f, 180f, 271f)) {
            val s = surface(346, 264)
            s.yawDegrees = yaw
            s.renderMode = RenderMode.ENTITIES
            s.backingBlock = BlockStateRef.BLACK_CONCRETE
            s.paint { label("align", 0, 0, DkColor.WHITE) }

            assertEquals(0, s.canvasItemCount(), "ENTITIES mode must not paint into the shared canvas")

            val back = s.toBackingEntity()!!
            val m = back.transformation.joml
            val corners = listOf(
                Triple(0, 0, 0f to 1f),
                Triple(s.widthPx, 0, 1f to 1f),
                Triple(0, s.heightPx, 0f to 0f),
                Triple(s.widthPx, s.heightPx, 1f to 0f)
            )
            for ((px, py, sc) in corners) {
                val expected = s.planePointForTest(px, py)
                val v = m.transformPosition(org.joml.Vector3f(sc.first, sc.second, 0f))
                val gap = Math.sqrt(
                    Math.pow(expected.x - (back.position.x + v.x()), 2.0) +
                        Math.pow(expected.y - (back.position.y + v.y()), 2.0) +
                        Math.pow(expected.z - (back.position.z + v.z()), 2.0)
                )
                // The only separation allowed is the deliberate depth step.
                val expectedGap = (Surface.LAYER_Z_STEP + s.backingThicknessBlocks).toDouble()
                assertTrue(
                    Math.abs(gap - expectedGap) < 1e-4,
                    "yaw $yaw corner ($px,$py): slab is $gap from the canvas bound, expected $expectedGap"
                )
            }
        }
    }

    // --- Fact 6: overlapping glyphs need real depth between them ---

    @Test
    fun eachLayerBecomesItsOwnEntitySteppedTowardTheViewer() {
        val s = surface()
        s.paint {
            fill(DkColor.WHITE, Rect(0, 0, 40, 40))   // chrome
            slot(10, 10)                               // slot
            label("hi", 12, 12, DkColor.WHITE)         // text
        }
        val es = s.toEntities()
        assertEquals(3, es.size, "one entity per occupied layer")

        // Layers must differ ONLY in depth: same block, same origin, so they
        // stack instead of sliding apart.
        val step = Surface.LAYER_Z_STEP.toDouble()
        for (i in 1 until es.size) {
            val a = es[i - 1].position
            val b = es[i].position
            assertTrue(
                Math.abs(a.x - b.x) < 1e-6,
                "layers must not drift sideways: ${a.x} vs ${b.x}"
            )
            assertEquals(a.y, b.y, "layers must not drift vertically")
        }
        // At yaw 0 the readable normal is +Z, so each layer steps along it.
        val zs = es.map { it.position.z }
        assertEquals(zs.sorted(), zs, "layers must be ordered back to front")
        assertTrue(
            Math.abs((zs[1] - zs[0]) - step) < 1e-6,
            "consecutive layers should be one depth step apart, got ${zs[1] - zs[0]}"
        )
    }

    @Test
    fun everyLayerResolvesTheSameBlockSoTheyCannotDriftApart() {
        // The nine-slice frame overshoots the canvas width by a pixel. If each
        // layer anchored to its own widest row the frame's block would be
        // wider than the icons' and the two would offset by half that.
        val s = surface()
        s.paint {
            fill(DkColor.WHITE, Rect(0, 0, s.widthPx, 40))
            label("x", 0, 0, DkColor.WHITE)
        }
        val es = s.toEntities()
        assertTrue(es.size >= 2)
        assertEquals(es.first().lineWidth, es.last().lineWidth, "shared block width")
    }

    @Test
    fun aWidgetDrawnOnChromeGetsItsOwnDepth() {
        // Layering by primitive KIND alone is not enough: a window frame and a
        // title bar drawn on top of it are both chrome, so they land on one
        // plane and z-fight exactly like unlayered glyphs. The widget helpers
        // raise an elevation for their own body; this checks they actually do.
        val s = surface()
        s.paint {
            fill(DkColor.WHITE, Rect(0, 0, s.widthPx, s.heightPx))   // base chrome
            elevate { fill(DkColor.WHITE, Rect(10, 10, 100, 16)) }   // a widget on it
        }
        val es = s.toEntities()
        assertEquals(2, es.size, "raised chrome must not share the base plane")
        assertTrue(es[1].position.z > es[0].position.z, "the widget sits in front")
    }

    @Test
    fun onlyTheBottomLayerPaintsABackground() {
        // A text display fills its whole measured block with backgroundColor.
        // Giving every layer one stacks N quads, each hiding the glyphs of the
        // layer beneath -- which reads as z-fighting but is pure occlusion.
        val s = surface()
        s.backdrop = DkColor(190, 18, 19, 22)
        s.paint {
            fill(DkColor.WHITE, Rect(0, 0, 40, 40))
            label("on top", 4, 4, DkColor.WHITE)
        }
        val es = s.toEntities()
        assertTrue(es.size >= 2)
        assertEquals(s.backdrop, es.first().backgroundColor, "bottom layer keeps the backdrop")
        for (e in es.drop(1)) {
            assertEquals(
                DkColor.TRANSPARENT, e.backgroundColor,
                "a layer above the bottom must not paint over the one below"
            )
        }
    }

    @Test
    fun aLayerCarriesOnlyItsOwnContent() {
        val s = surface()
        s.paint {
            fill(DkColor.WHITE, Rect(0, 0, 40, 40))
            label("UNIQUEMARKER", 4, 4, DkColor.WHITE)
        }
        val es = s.toEntities()
        val carrying = es.count { it.text.plain().contains("UNIQUEMARKER") }
        assertEquals(1, carrying, "the label must appear in exactly one layer, not all of them")
    }

    // --- Scaling: an oversized sprite must fit its cell ---

    @Test
    fun fitHeightNeverLetsASpriteExceedItsBox() {
        val wide = SpriteEntry(
            id = SpriteId("gui", "wide"), width = 200, height = 26,
            texture = "minecraft:wide.png", trimmedWidth = 200
        )
        val h = wide.fitHeight(16, 16)
        assertTrue(wide.scaledWidth(h) <= 16, "scaled width ${wide.scaledWidth(h)} must fit 16")
        assertTrue(h <= 16, "scaled height $h must fit 16")
        assertTrue(h >= 1, "a glyph height of 0 is rejected by the client")

        val tall = SpriteEntry(
            id = SpriteId("gui", "tall"), width = 26, height = 200,
            texture = "minecraft:tall.png", trimmedWidth = 26
        )
        val th = tall.fitHeight(16, 16)
        assertTrue(tall.scaledWidth(th) <= 16 && th <= 16)
    }

    @Test
    fun aScaledGlyphAdvancesByItsScaledWidth() {
        // The client advances by round(trimmedWidth * scale) + 1. Advancing by
        // the NATIVE width would push everything after a scaled sprite far to
        // the right -- the same accumulation bug as the untrimmed advance.
        val e = SpriteEntry(
            id = SpriteId("gui", "big"), width = 256, height = 256,
            texture = "minecraft:big.png", trimmedWidth = 256
        )
        assertEquals(257, e.scaledAdvance(256), "1:1 is trimmedWidth + 1")
        assertEquals(17, e.scaledAdvance(16), "scaled to 16px it advances 17, not 257")
    }

    @Test
    fun scalingAGlyphAllocatesADistinctVariant() {
        // renderHeight is part of the font entry, so the same sprite at two
        // sizes needs two codepoints -- sharing one would render both at
        // whichever height was registered last.
        val e = SpriteEntry(
            id = SpriteId("gui", "v"), width = 32, height = 32,
            texture = "minecraft:v.png", trimmedWidth = 32
        )
        val a = SpriteGlyphs.codepointFor(e, ascent = 7, renderHeight = 32)
        val b = SpriteGlyphs.codepointFor(e, ascent = 7, renderHeight = 16)
        assertTrue(a != b, "distinct render heights must not share a codepoint")
        assertEquals(a, SpriteGlyphs.codepointFor(e, ascent = 7, renderHeight = 32), "stable")
    }

}