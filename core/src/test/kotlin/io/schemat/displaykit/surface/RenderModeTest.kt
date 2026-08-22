package io.schemat.displaykit.surface

import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.NineSlice
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RenderMode]: AUTO's resolution against [SliceGlyphSource.installed], and
 * the zero-pack [RenderMode.ENTITIES] render path itself.
 *
 * Kept separate from [SurfaceTest], which deliberately forces
 * [RenderMode.COMPOSITED] throughout (see its own comment) so its
 * pack-fallback tests keep testing exactly what they always tested. This
 * file is free to leave [SliceGlyphSource] uninstalled as ITS default,
 * matching how [RenderMode.ENTITIES] is meant to be exercised.
 */
class RenderModeTest {

    private object FakeSliceSource : SliceGlyphSource {
        override fun request(id: SpriteId) {}
        override fun codepointFor(id: SpriteId, srcX: Int, srcY: Int, ascent: Int) = 0xF8000
        override fun advanceFor(id: SpriteId, srcX: Int, srcY: Int): Int? = null
    }

    @BeforeTest fun clearSliceSource() { SliceGlyphSource.installed = null }
    @AfterTest fun restoreSliceSource() { SliceGlyphSource.installed = null }

    private fun surface(w: Int = 200, h: Int = 120, yaw: Float = 0f) =
        Surface(w, h, Vec3d(0.0, 70.0, 0.0), targetWidthBlocks = 2f).apply { yawDegrees = yaw }

    // --- AUTO resolution ---

    @Test
    fun autoResolvesToEntitiesWithNoSliceGlyphSourceInstalled() {
        SliceGlyphSource.installed = null
        assertEquals(RenderMode.ENTITIES, RenderMode.resolve(RenderMode.AUTO))
        assertEquals(RenderMode.ENTITIES, surface().effectiveRenderMode())
    }

    @Test
    fun autoResolvesToCompositedWithASliceGlyphSourceInstalled() {
        SliceGlyphSource.installed = FakeSliceSource
        assertEquals(RenderMode.COMPOSITED, RenderMode.resolve(RenderMode.AUTO))
        assertEquals(RenderMode.COMPOSITED, surface().effectiveRenderMode())
    }

    @Test
    fun explicitModesIgnoreSliceGlyphSourceEntirely() {
        SliceGlyphSource.installed = null
        assertEquals(RenderMode.COMPOSITED, RenderMode.resolve(RenderMode.COMPOSITED))
        assertEquals(RenderMode.ENTITIES, RenderMode.resolve(RenderMode.ENTITIES))
    }

    // --- entity counts: the toggle must provably change something ---

    private val icon8 = SpriteEntry(
        id = SpriteId("items", "test_icon"), width = 8, height = 8,
        texture = "minecraft:item/test_icon.png"
    )

    private fun paintTwoIconsAndALabel(p: SurfacePainter) {
        p.icon(icon8, 0, 0)
        p.icon(icon8, 20, 0)
        p.label("hi", 40, 0)
    }

    @Test
    fun entitiesModeEmitsOneEntityPerPaintedElement() {
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        s.paint { paintTwoIconsAndALabel(this) }
        assertEquals(3, s.toEntities().size, "one entity per icon()/icon()/label() call")
    }

    @Test
    fun compositedModeEmitsOneEntityPerDepthLayerNotPerElement() {
        val s = surface().apply { renderMode = RenderMode.COMPOSITED }
        s.paint { paintTwoIconsAndALabel(this) }
        // Both icon() calls share KIND_ICON's one canvas layer; label() is a
        // second, KIND_TEXT layer. 2 layers from 3 paint calls is exactly
        // what proves the two modes are doing something different, not just
        // relabelling the same output.
        assertEquals(2, s.toEntities().size, "one entity per depth layer, not per paint call")
    }

    // --- non-uniform scale preserves aspect ratio ---

    @Test
    fun nonUniformScalePreservesAspectRatioWhenFittingASprite() {
        // A 200x26 sprite (a vanilla button-shaped panel) fitted into a 32x32
        // slot cell -- the picker's own shape (iconFitted into an 18x18-ish
        // cell), just with a much wider-than-tall source.
        val wide = SpriteEntry(
            id = SpriteId("gui", "wide_banner"), width = 200, height = 26,
            texture = "minecraft:gui/wide_banner.png"
        )
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        s.paint { iconFitted(wide, 0, 0, 32, 32) }
        val e = s.toEntities().single()

        // Yaw 0 -> transformation is a pure diagonal scale (see Surface.spriteEntity),
        // so the JOML column-major float array's m00/m11 ARE scaleX/scaleY directly.
        val m = e.transformation.toFloatArray()
        val scaleX = m[0]
        val scaleY = m[5]
        val gotRatio = scaleX / scaleY
        val wantRatio = wide.width.toFloat() / wide.height.toFloat()
        assertTrue(
            abs(gotRatio - wantRatio) / wantRatio < 0.05f,
            "x/y scale ratio $gotRatio must track the sprite's own aspect ratio $wantRatio within rounding"
        )
    }

    // --- canvas -> world placement agrees with SurfacePicking ---

    /**
     * If [RenderMode.ENTITIES] placed an element anywhere other than exactly
     * where [SurfacePicking] would resolve that same canvas pixel back to,
     * the UI would render in one place and accept clicks in another. Same
     * round-trip shape as [SurfacePointerTest.thePointerLandsUnderTheCrosshairAtNonZeroYaw],
     * because this is the same underlying claim: rendering and picking share
     * one canvas-to-world mapping.
     */
    @Test
    fun entitiesModePlacementAgreesWithSurfacePicking() {
        // 8x8 native icon -- sx == sy == pixelScale, so inverting Surface.elementOrigin
        // by hand (below) only needs the SAME uniform unit entityOrigin itself uses.
        val icon = SpriteEntry(
            id = SpriteId("items", "marker"), width = 8, height = 8,
            texture = "minecraft:item/marker.png"
        )
        val rect = Rect(30, 40, 8, 8)

        for (yaw in listOf(0f, 90f, 137f, 271f)) {
            val s = surface(yaw = yaw).apply { renderMode = RenderMode.ENTITIES }
            s.paint { icon(icon, rect.x, rect.y) }
            val entity = s.toEntities().single()

            // Undo exactly Surface.elementOrigin's correction (unit=PIXEL_SIZE*pixelScale,
            // blockWidthPx=8, blockHeightPx=FONT_LINE_HEIGHT_PX-1, depth=0 -- the only
            // element, so ordinal 0) to recover the anchor it was built from.
            val unit = TextMetrics.PIXEL_SIZE * s.pixelScale.toDouble()
            val localX = unit * (1.0 - 8 / 2.0)
            // Mirrors Surface.spriteEntity's blockHeightPx. Note what this
            // test can and cannot prove: it inverts elementOrigin using the
            // same constant elementOrigin was given, so it is self-consistent
            // BY CONSTRUCTION and stayed green for as long as every sprite in
            // ENTITIES mode rendered h/8 canvas pixels too high. What it does
            // prove is that rendering and picking share one mapping -- worth
            // having, but it is not an absolute check. That comes from
            // measurement against the backing slab; see CalibrationWindow.
            val localY = unit * TextMetrics.FONT_LINE_HEIGHT_PX.toDouble()
            val theta = Math.toRadians(yaw.toDouble())
            val cos = cos(theta)
            val sin = sin(theta)
            val anchor = Vec3d(
                entity.position.x + localX * cos,
                entity.position.y + localY,
                entity.position.z - localX * sin
            )

            // That anchor must be EXACTLY the plane point SurfacePicking would
            // resolve pixel (rect.x, rect.y) to -- the whole claim this test exists
            // to check, not just a round trip through the ray maths below.
            assertTrue(
                s.planePointForTest(rect.x, rect.y).distance(anchor) < 1e-9,
                "yaw $yaw: entity anchor must equal planePoint(rect.x, rect.y) exactly"
            )

            // And SurfacePicking, given a ray through that anchor, must resolve
            // back to the same pixel -- the actual click-vs-render agreement.
            val onPlane = s.planePointForTest(0, 0)
            val offPlane = s.planePointForTest(0, 0, 1f)
            val normal = offPlane - onPlane
            val eye = anchor + normal * 2.0
            val look = Vec3d(0.0, 0.0, 0.0) - normal
            val back = SurfacePicking.localPixel(s, eye, look)
            if (back != null) assertEquals(rect.x to rect.y, back, "yaw $yaw pixel (${rect.x},${rect.y})")
        }
    }

    // --- depth stepping: by DISTINCT depthKey, not by element count ---

    /**
     * Regression for a real in-world bug: [Surface.toEntitiesFlat] used to
     * step depth by each element's raw ordinal in the sorted list, so a
     * ~120-element page put ~1.2 blocks of physical depth between its first
     * and last element -- the window rendered as a wedge, not a flat panel.
     * Depth only has to separate elements that can actually overlap, which
     * is exactly what `depthKey` (KIND_CHROME/SLOT/ICON/TEXT) already
     * encodes: 100 same-kind icons are 100 disjoint rects, not 100 depths.
     */
    @Test
    fun depthSpreadIsBoundedByDistinctDepthKeysNotElementCount() {
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        s.paint {
            fill(io.schemat.displaykit.render.DkColor.WHITE, Rect(0, 0, 16, 16)) // KIND_CHROME
            slot(20, 0) // KIND_SLOT
            repeat(97) { i -> icon(icon8, 40 + i, 0) } // KIND_ICON, all one key
            label("hi", 0, 20) // KIND_TEXT
        }
        val entities = s.toEntities()
        assertEquals(100, entities.size, "one entity per painted element, unaffected by this fix")

        // At yaw 0, planePoint's z term vanishes regardless of (px, py), so
        // entity.position.z - surface.position.z IS exactly this element's
        // depth (see Surface.elementOrigin/planePoint) -- a direct, exact
        // read of the bug this test guards, not an approximation of it.
        val depths = entities.map { it.position.z - s.position.z }
        val spread = depths.max() - depths.min()

        val fourKeys = 3 * Surface.LAYER_Z_STEP // KIND_CHROME..KIND_TEXT: 4 distinct keys, 3 steps between them
        val perElement = 99 * Surface.LAYER_Z_STEP // the old, buggy behaviour this must NOT match
        assertTrue(
            abs(spread - fourKeys) < 1e-6,
            "depth spread $spread must equal (distinct key count - 1) * LAYER_Z_STEP = $fourKeys, " +
                "not grow with element count ($perElement would be the old per-element bug)"
        )
    }

    // --- frame(): corner-occlusion geometry ---

    private val ninesliceFrame = SpriteEntry(
        id = SpriteId("gui", "test_frame"), width = 16, height = 16,
        texture = "minecraft:gui/test_frame.png",
        nineSlice = NineSlice(left = 3, top = 3, right = 3, bottom = 3),
        // An opaque centre, so the flat-fill substitution applies at all --
        // a null averageColor means "this region draws nothing", and the
        // background assertions below would then be asserting the absence
        // they are meant to prove present. See SpriteEntry.averageColor.
        averageColor = 0x808080
    )

    @Test
    fun frameAnchorsAllFourCornersAtNativeScaleOnTheRectsCorners() {
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        val rect = Rect(10, 10, 60, 50)
        s.paint { frame(ninesliceFrame, rect) }
        val rects = s.paintedSpriteRectsForTest()

        assertTrue(Rect(rect.x, rect.y, 16, 16) in rects, "top-left corner, native size, anchored at the rect's top-left")
        assertTrue(
            Rect(rect.right - 16, rect.y, 16, 16) in rects,
            "top-right corner, native size, anchored at the rect's top-right (spill runs left/down)"
        )
        assertTrue(
            Rect(rect.x, rect.bottom - 16, 16, 16) in rects,
            "bottom-left corner, native size, anchored at the rect's bottom-left"
        )
        assertTrue(
            Rect(rect.right - 16, rect.bottom - 16, 16, 16) in rects,
            "bottom-right corner, native size, anchored at the rect's bottom-right"
        )
    }

    @Test
    fun frameInsetsTheBackgroundByExactlyTheNineSliceInsets() {
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        val rect = Rect(10, 10, 60, 50)
        s.paint { frame(ninesliceFrame, rect) }
        val rects = s.paintedSpriteRectsForTest()

        val slice = ninesliceFrame.nineSlice!!
        val expectedBackground = Rect(
            rect.x + slice.left, rect.y + slice.top,
            rect.w - slice.left - slice.right, rect.h - slice.top - slice.bottom
        )
        assertTrue(
            expectedBackground in rects,
            "background must be inset by exactly the nine-slice insets, so it occludes precisely " +
                "what the corners spilled and nothing more"
        )
    }

    @Test
    fun frameAtExactlyNativeSizeIsDrawnOnceRatherThanSliced() {
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        // Exactly the sprite's own 16x16: nothing needs stretching, so there
        // is nothing for slicing to buy. It used to emit four native-size
        // corners plus five flat fills -- nine entities reproducing, badly,
        // what one draw renders exactly. `gui/widget/tab_selected` is drawn
        // at native size by the picker and came out a solid white box.
        val rect = Rect(10, 10, 16, 16)
        s.paint { frame(ninesliceFrame, rect) }
        val rects = s.paintedSpriteRectsForTest()

        assertEquals(
            listOf(rect), rects,
            "a frame at native size must be exactly one sprite at the rect"
        )
    }

    @Test
    fun frameWithAFullyTransparentCentreSubstitutesNoFill() {
        // averageColor = null is the generator saying "no opaque pixels
        // here". Vanilla draws nothing in this region, so an opaque slab is
        // strictly worse than leaving it empty.
        val hollow = SpriteEntry(
            id = SpriteId("gui", "hollow_frame"), width = 16, height = 16,
            texture = "minecraft:gui/hollow_frame.png",
            nineSlice = NineSlice(left = 3, top = 3, right = 3, bottom = 3),
            averageColor = null
        )
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        val rect = Rect(10, 10, 60, 50)
        s.paint { frame(hollow, rect) }
        val rects = s.paintedSpriteRectsForTest()

        assertEquals(
            4, rects.size,
            "only the four corners may be emitted; every flat fill must be skipped, got $rects"
        )
        val background = Rect(rect.x + 3, rect.y + 3, rect.w - 6, rect.h - 6)
        assertTrue(background !in rects, "the background slab must not be painted at all")
    }

    @Test
    fun frameWithAnOpaqueCentreStillGetsItsFill() {
        // The negative control for the test above: same shape, same rect, the
        // only difference is a measured colour -- and the fill comes back. So
        // the skip is driven by the measurement, not by the geometry.
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        val rect = Rect(10, 10, 60, 50)
        s.paint { frame(ninesliceFrame, rect) }
        val rects = s.paintedSpriteRectsForTest()

        assertTrue(
            rects.size > 4,
            "an opaque-centred frame must still emit its fills, or the null case proves nothing"
        )
    }

    @Test
    fun frameWithNoNineSliceMetadataFallsBackToASingleStretchedSprite() {
        val flat = SpriteEntry(
            id = SpriteId("gui", "flat_panel"), width = 16, height = 16,
            texture = "minecraft:gui/flat_panel.png"
        )
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        val rect = Rect(5, 5, 40, 40)
        s.paint { frame(flat, rect) }
        assertEquals(listOf(rect), s.paintedSpriteRectsForTest(), "no nine-slice metadata -> one whole-sprite stretch, no corners")
    }

    // --- tint survives into the emitted component ---

    /**
     * `core`'s half of a real in-world bug: tinted fills/frame backgrounds
     * rendered as the raw white sprite because `fabric`'s
     * `FabricPlayerRef.createSpriteComponent` did not apply the component's
     * colour when building the `AtlasSprite` content -- see
     * `task-nopack-report.md`. `fabric` has no test source set (checked:
     * `libs/displaykit/fabric` has no `src/test`), so the fabric-side fix
     * itself is unverified by any automated test; this pins CORE's
     * contribution to the bug's fix -- that [Surface.spriteEntity] actually
     * puts the tint ON the component it hands to the platform layer in the
     * first place, which is the one half of the bug `core` can prove.
     */
    @Test
    fun tintedSpriteElementsCarryTheirColourOnTheEmittedComponent() {
        val tint = io.schemat.displaykit.render.DkColor(255, 12, 34, 56)
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        s.paint { icon(icon8, 0, 0, tint) }
        val text = (s.toEntities().single() as VirtualTextDisplay).text
        assertEquals(tint, text.color, "the sprite's TextComponent must carry the tint, not just the sprite id")
        assertEquals("items", text.sprite?.atlas)
    }

    // --- the identifier mapping a sprite entity carries ---

    @Test
    fun spriteEntitiesCarryTheSpriteIdsAtlasAndNameForANonDefaultAtlas() {
        // "gui" specifically -- AtlasSprite.DEFAULT_ATLAS is minecraft:blocks
        // (see FabricPlayerRef/Sprites in the fabric module), so a "blocks"
        // sprite would still resolve even if the atlas field were silently
        // dropped. "gui" is the case that actually proves the field made it
        // through core's TextComponent, across to fabric's AtlasSprite.
        val entry = SpriteEntry(
            id = SpriteId("gui", "widget/button"), width = 8, height = 8,
            texture = "minecraft:gui/widget/button.png"
        )
        val s = surface().apply { renderMode = RenderMode.ENTITIES }
        s.paint { icon(entry, 0, 0) }
        val sprite = (s.toEntities().single() as VirtualTextDisplay).text.sprite
        assertEquals("gui", sprite?.atlas)
        assertEquals("widget/button", sprite?.name)
    }
}
