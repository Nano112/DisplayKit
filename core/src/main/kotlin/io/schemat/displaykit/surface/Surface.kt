package io.schemat.displaykit.surface

import io.schemat.displaykit.composite.DepthAllocator
import io.schemat.displaykit.composite.DepthLayer
import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.Billboard
import io.schemat.displaykit.render.Brightness
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextAlignment
import io.schemat.displaykit.render.TextComponent
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.VirtualBlockDisplay
import io.schemat.displaykit.render.VirtualEntity
import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.sprite.SpriteCanvas
import io.schemat.displaykit.sprite.SpriteDiagnostics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteFit
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.layout.BoxNode
import io.schemat.displaykit.surface.layout.PxConstraints
import io.schemat.displaykit.surface.layout.PxOffset
import io.schemat.displaykit.surface.layout.SurfaceNode
import io.schemat.displaykit.surface.layout.WidgetNode
import org.joml.Matrix4f
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a surface can be painted with.
 *
 * Deliberately *parts*, not widgets. A button is a frame plus a label plus a
 * region; callers assemble what they need instead of picking from a fixed
 * catalogue. Composition helpers live in SurfaceParts.kt and are ordinary
 * functions over these calls, not a privileged API.
 */
interface SurfacePainter {
    /**
     * Give every primitive painted by [block] a stable composition identity.
     * Layout traversal supplies widget ids automatically; custom painters can
     * use this when their primitives need to survive a changing layer count.
     */
    fun identity(key: String, block: SurfacePainter.() -> Unit)

    fun frame(
        entry: SpriteEntry,
        rect: Rect,
        tint: DkColor? = null,
        depthOffset: Float = 0f
    )
    fun fill(color: DkColor, rect: Rect)
    fun icon(entry: SpriteEntry, x: Int, y: Int, tint: DkColor? = null)

    /**
     * Paint a sprite as a flush coating on the current chrome face.
     * Composited mode emits it into the face's own physical display layer.
     */
    fun faceIcon(
        entry: SpriteEntry,
        x: Int,
        y: Int,
        tint: DkColor? = null,
        depthOffset: Float = 0f
    )

    /**
     * Draw [entry] scaled to fit inside a [boxW] x [boxH] box at ([x], [y]),
     * centred, without distorting it.
     *
     * A sprite drawn at native size overflows any cell smaller than itself —
     * gui sprites are whole panels, hundreds of pixels across, so a grid of
     * them at 1:1 is unreadable. Scaling is free: a bitmap glyph's rendered
     * size is its provider `height`, so this costs a font entry, not a
     * texture.
     *
     * Returns the size it actually rendered at.
     */
    fun iconFitted(
        entry: SpriteEntry,
        x: Int,
        y: Int,
        boxW: Int,
        boxH: Int,
        tint: DkColor? = null
    ): Pair<Int, Int>

    /**
     * Fit a sprite like [iconFitted], but paint it into the current chrome
     * plane. This is for textured backgrounds that must remain behind later
     * fills and controls according to painter order.
     */
    fun chromeIconFitted(
        entry: SpriteEntry,
        x: Int,
        y: Int,
        boxW: Int,
        boxH: Int,
        tint: DkColor? = null
    ): Pair<Int, Int>
    fun label(text: String, x: Int, y: Int, color: DkColor? = null)

    /**
     * Paint text as part of the current chrome face rather than as a new
     * raised surface layer. Composited mode emits it into the face's physical
     * display and compensates Minecraft's plain-text row anchor. Entity mode
     * uses only a sub-millimetre render bias.
     */
    fun faceLabel(
        text: String,
        x: Int,
        y: Int,
        color: DkColor? = null,
        depthOffset: Float = 0f
    )

    /**
     * Draw a real block filling [rect], standing [thickness] blocks proud of
     * the panel.
     *
     * The one painter call that is NOT a flat plane. A block display is
     * world-lit, carries a real material and has genuine depth, so this is
     * for things a sprite genuinely cannot be -- a bezel with real edges, a
     * lever, a physical-looking readout -- not for coloured rectangles, which
     * [fill] does far more cheaply.
     *
     * Flat sprites and labels are composited when a pack is available, but
     * the block itself remains a block display in either render mode. This
     * makes it possible to put dense sprite artwork on a genuinely solid
     * control without forcing the entire surface into entity-per-glyph mode.
     */
    fun blockPanel(block: BlockStateRef, rect: Rect, thickness: Float = 0.0625f)

    /**
     * Draw a real block whose front face sits on this layer and whose volume
     * extends behind it.
     *
     * This is the solid backing for a sprite-faced control. Unlike
     * [blockPanel], its thickness is local to the control and therefore does
     * not push unrelated, later-painted surface layers toward the viewer.
     */
    fun blockBacking(block: BlockStateRef, rect: Rect, thickness: Float = 0.0625f)

    /**
     * Draw a local solid extrusion from the current surface plane toward the
     * viewer without advancing unrelated elements in the depth allocator.
     */
    fun blockExtrusion(block: BlockStateRef, rect: Rect, thickness: Float = 0.0625f)

    fun slot(x: Int, y: Int, item: ItemRef? = null)
    fun region(id: String, rect: Rect, onClick: () -> Unit)

    /**
     * Draw [block] raised [delta] elevations above the current one.
     *
     * Anything drawn on top of something else needs this, or the two share a
     * plane and z-fight. Widget helpers in `SurfaceParts` already do it; call
     * it directly when composing your own nested chrome.
     */
    fun elevate(delta: Int = 1, block: SurfacePainter.() -> Unit)
}

/**
 * A flat plane of pixels in the world that resolves to ONE entity.
 *
 * Within a surface there is no z — layering is draw order — which is what
 * removes the air gaps that made block-display widgets read as a scatter of
 * floating objects. Auxiliary entities that cannot live in a text component
 * (an item inside a slot) sit at [OVERLAY_Z_STEP] in front of the plane; call
 * sites never choose a z.
 */
class Surface(
    val widthPx: Int,
    requestedHeightPx: Int,
    var position: Vec3d,
    var targetWidthBlocks: Float,
    val orientation: Billboard = Billboard.FIXED
) {
    /**
     * Canvas height, rounded up so the client's measured text block is
     * exactly this tall.
     *
     * A block measures `rows * FONT_LINE_HEIGHT_PX - 1`, so an arbitrary
     * height is rounded up by the renderer anyway -- a 264px surface measures
     * 269 -- and everything sized from the block then sits a few pixels
     * proud of the sprite plane. Rounding here makes block == canvas an
     * invariant instead of a coincidence. See TextMetrics.exactBlockHeight.
     */
    val heightPx: Int = TextMetrics.exactBlockHeight(requestedHeightPx)

    companion object {
        /** The single depth step for anything that must sit in front of the plane. */
        const val OVERLAY_Z_STEP = 0.005f

        /**
         * Depth between consecutive surface layers, in blocks.
         *
         * Modestly larger than [OVERLAY_Z_STEP]. The client renders
         * text-display glyphs through `Font$DisplayMode.POLYGON_OFFSET`, which
         * already biases their depth, so leave a little headroom above that
         * bias — but only a little: a surface stacks one layer per elevation
         * per kind, and the total thickness is what eventually reads as an air
         * gap when the panel is viewed from an angle.
         *
         * 1cm per ordinary layer leaves enough headroom for unrelated
         * overlapping controls. A laminated control uses the dedicated face
         * APIs instead of consuming these global layer steps.
         */
        const val LAYER_Z_STEP = 0.01f

        /**
         * Render-only bias for artwork bonded to a solid face.
         *
         * This is deliberately microscopic: enough to give Minecraft's depth
         * buffer a deterministic winner, but two orders of magnitude smaller
         * than an ordinary UI layer so it cannot read as a detached sheet at
         * a grazing angle.
         */
        internal const val FACE_COATING_Z_BIAS = 0.0001f

        /**
         * Canvas-layer namespace for a face coating.
         *
         * A coating must be a separate text display because overlapping
         * glyph quads inside one display z-fight. It must *not*, however,
         * consume a normal [LAYER_Z_STEP]. Encoding its owning chrome layer
         * here lets [toEntitiesComposited] give it the same allocation key
         * plus only [FACE_COATING_Z_BIAS].
         */
        private const val FACE_LAYER_BASE = 1_000_000

        /** Local separation for entity quads that actually overlap. */
        internal const val ENTITY_OVERLAY_Z_BIAS = 0.0002f

        /**
         * Depth layers, back to front. Each becomes its own text display,
         * stepped [OVERLAY_Z_STEP] nearer the viewer than the one below, so
         * overlapping glyphs have real depth between them instead of
         * z-fighting.
         *
         * The order is painter's-algorithm: chrome, then the wells cut into
         * it, then their contents, then text on top of everything.
         */
        const val KIND_CHROME = 0
        const val KIND_SLOT = 1
        const val KIND_ICON = 2
        const val KIND_TEXT = 3

        /** Depth slots reserved per elevation, so kinds never collide across them. */
        const val KINDS_PER_ELEVATION = 4

        /**
         * Yaw, in degrees, that turns a surface's readable side toward a
         * viewer looking along [look].
         *
         * A text display's readable side is local **+Z**, not -Z. The client
         * bakes a `Matrix4f.rotate(PI, 0, 1, 0)` into every text display
         * before its `-0.025` scale (`DisplayRenderer$TextDisplayRenderer`,
         * offset 137), so the glyphs face the opposite way from the naive
         * expectation. Turning `atan2(look.x, look.z)` toward the viewer
         * therefore presents the surface's BACK, and anything placed at +Z to
         * sit "behind" the plane lands in front of it instead.
         *
         * The same 180-degree correction was previously discovered and
         * patched locally in the world-quad demos; it belongs here so every
         * call site gets it.
         *
         * See `docs/superpowers/specs/2026-08-21-text-display-layout-truth.md`.
         */
        @JvmStatic
        fun yawFacing(look: Vec3d): Float =
            Math.toDegrees(atan2(look.x, look.z)).toFloat() + 180f

        /** Uniform, fully opaque, pure white — the only vanilla sprite that tints exactly. */
        val FILL_SPRITE = SpriteId("blocks", "block/lightning_rod_on")

        val SLOT_SPRITE = SpriteId("gui", "container/slot")

        /**
         * Slack added on top of [SpriteCanvas.maxRowAdvance] when deriving
         * `lineWidth` in [toEntity], so a row's `lineWidth` sits strictly
         * past its true width rather than flush against it.
         */
        private const val LINE_WIDTH_MARGIN_PX = 8

        /**
         * Cursor sprite. 15x15 and greyscale, so it tints — glyph tint is
         * multiplicative and only greyscale sources tint cleanly.
         */
        val POINTER_SPRITE = SpriteId("gui", "hud/crosshair")

        /** Test seam for the missing-sprite path. */
        @JvmStatic
        var pointerSpriteOverride: SpriteId? = null

        /**
         * Width AND height, in font pixels, of vanilla's atlas-sprite text
         * component glyph -- decompiled from the 1.21.11 client:
         * `PlainTextRenderable.width()/height()/ascent()` on an `AtlasSprite`
         * content all return `8.0f`, backed by one shared
         * `AtlasGlyphProvider.GLYPH_INFO = GlyphInfo.simple(8.0f)`. Fixed and
         * client-built-in -- unrelated to the sprite's own pixel dimensions
         * or to any resource pack -- which is exactly why [RenderMode.ENTITIES]
         * has to recover size and aspect with an entity-level scale instead.
         */
        internal const val ATLAS_SPRITE_GLYPH_PX = 8f
    }

    init {
        require(widthPx > 0) { "Surface widthPx must be positive (got $widthPx)." }
        // The REQUESTED height, not the rounded one: exactBlockHeight floors
        // at one row, so validating the rounded value would silently accept
        // zero and negatives.
        require(requestedHeightPx > 0) {
            "Surface heightPx must be positive (got $requestedHeightPx)."
        }
        require(targetWidthBlocks > 0) {
            "Surface targetWidthBlocks must be positive (got $targetWidthBlocks)."
        }
    }

    /**
     * How this surface turns painted content into entities -- see
     * [RenderMode]. Defaults to [RenderMode.AUTO], which renders with the
     * generated resource pack when one is installed and degrades to
     * zero-pack atlas-sprite entities when it is not.
     */
    var renderMode: RenderMode = RenderMode.AUTO

    /**
     * Client-side smoothing applied when a painted entity changes transform or
     * position between repaints. Two ticks bridges the server's 20 Hz update
     * cadence without making direct manipulation feel delayed.
     *
     * Composited glyphs whose coordinates change *inside* one text component
     * cannot interpolate; moving canvas widgets should paint their moving
     * parts as face entities so this policy can move the entities themselves.
     */
    var motionInterpolationTicks: Int = 2
        set(value) {
            require(value in 0..59) { "motion interpolation must be between 0 and 59 ticks" }
            field = value
        }

    /** [renderMode] with [RenderMode.AUTO] resolved against [SliceGlyphSource.installed]. */
    internal fun effectiveRenderMode(): RenderMode = RenderMode.resolve(renderMode)

    /**
     * Optional solid panel behind the whole composition. Null (the default)
     * keeps the previous fully transparent background.
     *
     * One field and zero extra entities, versus a second stretched entity
     * that would cost an entity and need its own sizing.
     *
     * [DkColor]'s first parameter is alpha, so lower values make the backdrop
     * more transparent in the same way as any vanilla text-display background.
     */
    var backdrop: DkColor? = null

    /**
     * A block display stretched to the surface's full size, sitting just
     * behind the sprite plane.
     *
     * Sprite glyphs are single-sided and unlit, so against open sky they are
     * hard to read and the window looks like scattered decals rather than one
     * panel. A backing block gives them something solid to sit on, and — being
     * a real block — it takes world lighting, so the panel behaves like an
     * object in the scene.
     *
     * Null leaves the surface unbacked (the previous behaviour). Prefer this
     * over [backdrop] for anything window-shaped: [backdrop] is the text
     * display's own background quad, which cannot be lit, cannot have depth,
     * and is clipped to the measured text block rather than the canvas.
     */
    var backingBlock: BlockStateRef? = null

    /**
     * How thick the [backingBlock] slab is, in blocks.
     *
     * Kept small deliberately. A little depth reads as a physical panel, but
     * once the gap between the sprite plane and its backing becomes visible
     * from an angle the illusion breaks and the UI looks like disconnected
     * floating surfaces.
     */
    var backingThicknessBlocks: Float = 0.02f

    /**
     * The stretched panel behind the sprite plane, or null when
     * [backingBlock] is unset.
     *
     * A block display's position IS its local origin (unlike a text display —
     * see [entityOrigin]), so this hangs off [position] directly. The unit
     * cube is translated down by the surface's height and back by
     * [OVERLAY_Z_STEP] before scaling, so it spans exactly the canvas bounds
     * and sits one depth step behind the glyphs — far enough not to z-fight,
     * close enough not to show an air gap.
     *
     * "Behind" is local NEGATIVE Z, because a text display's readable side is
     * local +Z (see [yawFacing]). Putting the slab at +Z parks it between the
     * viewer and the glyphs, which hides the entire UI behind a blank
     * panel.
     *
     * Sized and anchored differently per [RenderMode] -- see
     * [backingEntityFromTextBlock] ([COMPOSITED]) and
     * [backingEntityFromCanvasBounds] ([ENTITIES]).
     */
    fun toBackingEntity(): VirtualBlockDisplay? {
        val block = backingBlock ?: return null
        val unit = TextMetrics.PIXEL_SIZE * pixelScale
        return if (effectiveRenderMode() == RenderMode.ENTITIES) {
            backingEntityFromCanvasBounds(block, unit)
        } else {
            backingEntityFromTextBlock(block, unit)
        }
    }

    /**
     * [RenderMode.COMPOSITED]'s backing slab: sized and anchored from the
     * composited TEXT BLOCK, not the canvas.
     *
     * The two are not the same: the block width is the widest emitted row
     * (the nine-slice frame's right edge overshoots the canvas by a pixel)
     * and the block height is rounded up to whole rows (264px of canvas
     * becomes 27 rows = 269px). A slab built from widthPx/heightPx therefore
     * never quite lines up, and the mismatch is visible as a dark margin
     * around the UI.
     */
    private fun backingEntityFromTextBlock(block: BlockStateRef, unit: Float): VirtualBlockDisplay {
        val blockW = canvas.blockWidthPx()
        val blockH = blockHeightPx()
        return VirtualBlockDisplay().also { d ->
            d.blockState = block
            // Anchored to the entity, which is what the block is measured
            // against -- position is the canvas top-left and drifts from the
            // block by the same rounding.
            d.position = entityOrigin()
            d.billboard = orientation
            d.brightness = Brightness.FULL
            d.transformation = Mat4f(
                Matrix4f()
                    .rotateY(Math.toRadians(yawDegrees.toDouble()).toFloat())
                    // The client's own x translate carries a +1 nudge
                    // (`1.0f - blockWidth / 2.0f`); match it or the slab sits
                    // a pixel off.
                    .translate(unit * (1f - blockW / 2f), 0f, -(LAYER_Z_STEP + backingThicknessBlocks))
                    .scale(unit * blockW, unit * blockH, backingThicknessBlocks)
            )
        }
    }

    /**
     * [RenderMode.ENTITIES]'s backing slab: sized and anchored from the
     * CANVAS BOUNDS directly, not any measured text block.
     *
     * Under [RenderMode.ENTITIES] nothing is ever painted into [canvas] --
     * [toEntitiesFlat] emits one entity per painted element instead -- so
     * [canvas.blockWidthPx]/[blockHeightPx] are meaningless
     * ([SpriteCanvas.emittedRowCount] is 0, and [blockHeightPx] computes
     * `0 * 10 - 1 = -1`), and [entityOrigin] early-returns [position]
     * unmodified because [SpriteCanvas.itemCount] is 0. Building the slab
     * from [widthPx]/[heightPx] and anchoring it at [position] -- which is
     * already defined as the canvas top-left -- sidesteps both: there is no
     * text-block centring to undo, because there is no text block.
     */
    private fun backingEntityFromCanvasBounds(block: BlockStateRef, unit: Float): VirtualBlockDisplay =
        VirtualBlockDisplay().also { d ->
            d.blockState = block
            d.position = position
            d.billboard = orientation
            d.brightness = Brightness.FULL
            d.transformation = Mat4f(
                Matrix4f()
                    .rotateY(Math.toRadians(yawDegrees.toDouble()).toFloat())
                    // No horizontal centring (unlike the text-block variant):
                    // position is already the left edge, and canvas +Y runs
                    // downward, so the slab hangs down-and-right from it.
                    .translate(0f, -(unit * heightPx), -(LAYER_Z_STEP + backingThicknessBlocks))
                    .scale(unit * widthPx, unit * heightPx, backingThicknessBlocks)
            )
        }

    /**
     * Y-axis rotation applied to the whole surface, in degrees, composed with
     * [toEntity]'s scale.
     *
     * An unrotated ([yawDegrees] `== 0f`) surface's readable side faces +Z
     * — the client's built-in `rotateY(PI)` flips it (see [yawFacing]). Use
     * [yawFacing] rather than deriving this angle by hand. (See
     * [SurfacePicking]'s KDoc). [SurfacePicking.localPixel] counter-rotates
     * the incoming eye/look by `-yawDegrees` about [position] before its
     * planar maths, so the two must stay in lockstep — this is the only
     * place either may change.
     */
    var yawDegrees: Float = 0f

    /** World blocks per canvas pixel, derived so callers size in blocks. */
    val pixelScale: Float
        get() = targetWidthBlocks / (widthPx * TextMetrics.PIXEL_SIZE)

    private val canvas = SpriteCanvas(widthPx, heightPx)
    private val rects = mutableListOf<HitRect>()
    private val slots = mutableListOf<Pair<Rect, ItemRef>>()

    /**
     * What [Painter] recorded as independent entities this paint. Entity mode
     * records every primitive here; composited mode may also record primitives
     * that cannot be represented losslessly by a canvas glyph (for example a
     * fill thinner than its source sprite) -- see [toEntitiesFlat].
     */
    private val elements = mutableListOf<EntityElement>()
    private var paintIdentity: String? = null
    private var paintIdentityOrdinal: Int = 0

    private fun recordElement(element: EntityElement) {
        element.reconcileKey = paintIdentity?.let { "$it/${paintIdentityOrdinal++}" }
        elements += element
    }

    /**
     * Test seam: the sprite rects [RenderMode.ENTITIES] recorded from the
     * most recent [paint], in paint order -- e.g. for [SurfacePainter.frame],
     * the four corners followed by the background/edge fills (see
     * [recordFrame]). Lets a test assert the corner-occlusion geometry
     * directly, without a client to actually render it.
     */
    /**
     * Test seam: each recorded sprite paired with the DEPTH KEY it was given.
     *
     * Counting distinct depth planes is not enough to prove two overlapping
     * layers were separated: a hollow nine-slice emits fewer planes than a
     * solid one (its substitute fills are skipped), which can exactly offset
     * an added ground and leave the totals identical while the two are still
     * coplanar. Pairing rect with depth lets a test name the two things it
     * cares about and compare THEM.
     */
    internal fun paintedSpriteDepthsForTest(): List<Pair<Rect, Double>> =
        elements.filterIsInstance<EntityElement.SpriteEl>().map { it.rect to it.depthKey }

    internal fun paintedSpriteRectsForTest(): List<Rect> =
        elements.filterIsInstance<EntityElement.SpriteEl>().map { it.rect }

    internal fun paintedLabelYsForTest(): List<Int> =
        elements.filterIsInstance<EntityElement.LabelEl>().map { it.y }

    internal fun paintedLabelXsForTest(): List<Int> =
        elements.filterIsInstance<EntityElement.LabelEl>().map { it.x }

    internal fun paintedLabelDepthsForTest(): List<Double> =
        elements.filterIsInstance<EntityElement.LabelEl>().map { it.depthKey }

    fun canvasItemCount(): Int = canvas.itemCount()
    fun canvasItemPositions(): List<Pair<Int, Int>> = canvas.itemPositions()
    fun hitRects(): List<HitRect> = rects.toList()
    fun slotItems(): List<Pair<Rect, ItemRef>> = slots.toList()

    /**
     * Root of this surface's layout tree, or null if [layout] was never called.
     *
     * Surfaces predate the tree, so it is optional: `paint {}` with absolute
     * rects still works, and [hitRects] still drives clicks for those. New
     * windows should use [layout], which is what gives events a parent chain
     * to bubble along.
     */
    var root: SurfaceNode? = null
        private set

    /**
     * Rebuild the layout tree, measure it against the canvas bounds, and place
     * it at the origin.
     *
     * Rebuilds from scratch each call, so a caller can re-run it after any
     * content change without tracking which nodes to remove.
     *
     * [layout] and [paint] are mutually exclusive, not layered: rendering the
     * tree ([paintTree]) goes through [paint], which clears the canvas first.
     * Calling [paint] directly after [layout] therefore DISCARDS the tree's
     * output rather than merging with it -- a tree-based surface must drive
     * its content through [paintTree], never a raw [paint] call, once [layout]
     * has been used.
     */
    fun layout(build: (SurfaceNode) -> Unit) {
        val r = BoxNode("surface-root")
        build(r)
        root = r
        measureTree()
    }

    /**
     * Re-measure and place the retained tree against the canvas.
     *
     * This runs before every tree paint. Dynamic labels, visibility changes,
     * and pages added to a keyed switch therefore participate in normal
     * composition instead of requiring feature code to patch coordinates.
     */
    private fun measureTree() {
        val r = root ?: return
        r.measure(PxConstraints.exactly(widthPx, heightPx))
        r.place(PxOffset.Zero)
        // Preparation belongs to the composed primitives, not the window
        // using them. It runs only for the glyph-backed renderer; entity mode
        // has no generated glyph variants to prepare.
        if (effectiveRenderMode() == RenderMode.COMPOSITED) {
            fun prepare(node: SurfaceNode) {
                node.onPrepare?.invoke()
                node.children.forEach(::prepare)
            }
            prepare(r)
        }
    }

    /** Paint every [WidgetNode] in the tree, in tree order (back to front). */
    fun paintTree() {
        val r = root ?: return
        measureTree()
        paint {
            fun walk(node: SurfaceNode) {
                if (node is io.schemat.displaykit.surface.layout.SurfacePaintNode) {
                    identity(node.id) { node.paint(this) }
                }
                val kids = if (node is io.schemat.displaykit.surface.layout.ChildViewport) {
                    node.visibleChildren()
                } else node.children
                kids.forEach(::walk)
            }
            walk(r)
        }
    }

    /** Deepest node at a canvas point, or null. */
    fun nodeAt(x: Int, y: Int): SurfaceNode? = root?.hitTest(x, y)

    /** Dispatch an event into the tree. Returns the consuming node, if any. */
    fun dispatch(event: SurfaceEvent, target: SurfaceNode? = null): SurfaceNode? {
        val r = root ?: return null
        return SurfaceEvents.dispatch(r, event, target)
    }

    /**
     * Repaint from scratch. Previous content, hit rects and slots are discarded.
     *
     * [layout] and [paint] are mutually exclusive, not layered: this clears
     * the canvas and sets [SpriteCanvas.anchorToBounds] before running
     * [block], so calling this directly on a surface built with [layout]
     * discards the tree's painted output rather than merging with it. Use
     * [paintTree] to (re-)render a tree-based surface instead of calling this
     * directly.
     */
    fun paint(block: SurfacePainter.() -> Unit) {
        canvas.anchorToBounds = true
        canvas.clear(); rects.clear(); slots.clear(); elements.clear()
        Painter().block()
    }

    /**
     * Everything the layer geometry depends on, for `-Ddisplaykit.debug.layers`.
     *
     * Per-layer row width is the value the client is expected to measure the
     * block at. If these differ between layers, each layer centres on a
     * different width and they slide apart horizontally.
     */
    internal fun describeLayersForDebug(spawned: List<VirtualEntity>): String {
        val mode = effectiveRenderMode()
        val sb = StringBuilder()
        sb.append("[DisplayKit] surface ${widthPx}x${heightPx} mode=$mode yaw=${"%.1f".format(yawDegrees)} ")
        sb.append("pixelScale=${"%.5f".format(pixelScale)} entities=${spawned.size}\n")
        sb.append("[DisplayKit]   position=${position}\n")
        if (mode == RenderMode.COMPOSITED) {
            sb.append("[DisplayKit]   block=${blockWidthPx()}x${blockHeightPx()}\n")
            for ((i, layer) in canvas.layers().withIndex()) {
                val rows = canvas.layerRowWidths(layer)
                val e = spawned.getOrNull(i) as? VirtualTextDisplay
                sb.append(
                    "[DisplayKit]   layer=$layer ordinal=$i maxRow=${rows.maxOrNull()} " +
                        "rows=${rows.size} items=${canvas.layerItemCount(layer)} " +
                        "pos=${e?.position} lineWidth=${e?.lineWidth}\n"
                )
            }
        } else {
            // ENTITIES: one entity per painted element -- no shared canvas
            // layers to describe, just the resulting entity count, which is
            // the whole point of measuring this mode against COMPOSITED's.
            for ((i, e) in spawned.withIndex()) {
                // Media are mixed on this path now, so name the kind: an
                // entity count alone no longer says what was emitted.
                val kind = when (e) {
                    is VirtualTextDisplay -> "text lineWidth=${e.lineWidth}"
                    is VirtualBlockDisplay -> "block state=${e.blockState}"
                    else -> e::class.simpleName
                }
                sb.append("[DisplayKit]   entity=$i pos=${e.position} $kind\n")
            }
        }
        return sb.toString().trimEnd()
    }

    /** Width of the text block the client will measure, in canvas pixels. */
    internal fun blockWidthPx(): Int = canvas.blockWidthPx()

    /** Height of that block, in canvas pixels. */
    internal fun blockHeightPx(): Int =
        canvas.emittedRowCount() * TextMetrics.FONT_LINE_HEIGHT_PX - 1

    /**
     * The world point to give the entity so that canvas pixel (0,0) — the
     * top-left, which is what [position] means — lands on [position].
     *
     * A text display is NOT positioned by its top-left. Verified in the
     * 1.21.11 client (`DisplayRenderer$TextDisplayRenderer.render`, offsets
     * 181-219), the renderer does
     *
     *     translate(1.0f - blockWidth / 2.0f, -(lineCount * 10 - 1), 0.0f)
     *
     * and then scales the whole matrix by `-0.025`. So the entity sits at the
     * horizontal CENTRE and the vertical BOTTOM of the measured text block,
     * one pixel off in x. Resolving the double negation, canvas pixel
     * `(px, py)` ends up at this offset from the entity, before [yawDegrees]:
     *
     *     dx = PIXEL_SIZE * scale * (blockWidth / 2 - 1 - px)
     *     dy = PIXEL_SIZE * scale * (blockHeight - py)
     *
     * A `rotateY(PI)` at offset 137 precedes that scale and cancels the
     * negation on x and z, so canvas +X is local +X and only canvas +Y is
     * flipped (it runs downward). Hence:
     *
     *     dx = PIXEL_SIZE * scale * (px + 1 - blockWidth / 2)
     *     dy = PIXEL_SIZE * scale * (blockHeight - py)
     *
     * `blockWidth` is [SpriteCanvas.maxRowAdvance] and `blockHeight` follows
     * from [SpriteCanvas.emittedRowCount]. Both are exact rather than
     * predicted: every glyph advance now matches what the client measures
     * (see [SpriteEntry.glyphAdvance]), and anchoring
     * ([SpriteCanvas.anchorToBounds]) floors the block at the canvas bounds so
     * it does not shift when a widget changes width.
     *
     * See `docs/superpowers/specs/2026-08-21-text-display-layout-truth.md`.
     *
     * @param source The canvas whose measured block ([SpriteCanvas.blockWidthPx],
     *   [SpriteCanvas.emittedRowCount]) the client will centre its render
     *   against — normally this surface's own [canvas], but [pointerEntityAt]
     *   passes a separate, unanchored canvas holding just the cursor glyph, so
     *   the pointer entity's placement is derived from ITS OWN (small) block
     *   rather than the whole surface's.
     * @param base Canvas pixel (0,0) of [source] lands here — normally
     *   [position], the surface's own anchor, but [pointerEntityAt] passes a
     *   point elsewhere ON the surface plane instead, since the pointer's
     *   canvas is not anchored at the surface's own origin.
     */
    internal fun entityOrigin(depth: Float = 0f, source: SpriteCanvas = canvas, base: Vec3d = position): Vec3d {
        // No text means no measured block and so no centring to undo.
        if (source.itemCount() == 0) return base
        val unit = TextMetrics.PIXEL_SIZE * pixelScale
        val blockHeightPx = source.emittedRowCount() * TextMetrics.FONT_LINE_HEIGHT_PX - 1
        // Offset of canvas (0,0) from the entity, in the surface's own frame.
        val localX = unit * (1.0 - source.blockWidthPx() / 2.0)
        val localY = unit * blockHeightPx.toDouble()
        val theta = Math.toRadians(yawDegrees.toDouble())
        val cos = cos(theta)
        val sin = sin(theta)
        // rotateY applied to local +X, matching SurfacePicking's inverse.
        // `depth` steps along local +Z, the readable side (see yawFacing), so
        // a higher layer sits nearer the viewer.
        return Vec3d(
            base.x - (localX * cos) + depth * sin,
            base.y - localY,
            base.z - (localX * -sin) + depth * cos
        )
    }

    /**
     * World point on the surface's own plane under canvas pixel ([px], [py]),
     * [depth] blocks toward the viewer from the plane itself.
     *
     * Pure plane geometry — unlike [entityOrigin] it does not depend on any
     * measured block width, because it is not undoing a text display's own
     * block-centring, only projecting a 2D canvas point into world space.
     * [pointerEntityAt] uses this to find where the aimed-at point sits before
     * handing that point to [entityOrigin] as its `base`.
     */
    private fun planePoint(px: Int, py: Int, depth: Float = 0f): Vec3d {
        val unit = TextMetrics.PIXEL_SIZE * pixelScale
        val theta = Math.toRadians(yawDegrees.toDouble())
        val cos = cos(theta)
        val sin = sin(theta)
        return Vec3d(
            position.x + unit * px * cos + depth * sin,
            position.y - unit * py,
            position.z - unit * px * sin + depth * cos
        )
    }

    /** Test seam for [planePoint], which otherwise stays private. */
    internal fun planePointForTest(px: Int, py: Int, depth: Float = 0f): Vec3d = planePoint(px, py, depth)

    /**
     * World point of a canvas pixel, for aiming a viewer at part of this
     * surface.
     *
     * Automated in-world clicking cannot be driven from screenshots: locating
     * a panel by "find the dark region" picks up night-time terrain, and
     * guessing a look angle misses. The server already knows where every
     * pixel of a surface is, so a test can ask for the exact world point and
     * aim at it. Same maths the renderer and the raycast use, so it cannot
     * disagree with either.
     */
    fun worldPointOf(px: Int, py: Int): Vec3d = planePoint(px, py)

    /**
     * [entityOrigin]'s formula, generalised to an independent scale per axis.
     *
     * [entityOrigin] folds the client's block-centring translate and its own
     * scale into ONE `unit`, because every [RenderMode.COMPOSITED] layer is
     * stretched uniformly. A [RenderMode.ENTITIES] sprite is not: recovering
     * its aspect ratio needs `targetW/8` on x and `targetH/8` on y
     * independently (see [RenderMode.ENTITIES]'s KDoc), so the translate's x
     * half must scale by [scaleX] and its y half by [scaleY] separately.
     * [yawDegrees] still rotates x/z together exactly as [entityOrigin]
     * does -- a text display's local y axis is never touched by rotateY --
     * so this is the same rotation, just fed two units instead of one.
     *
     * With `scaleX == scaleY == pixelScale` and a [blockWidthPx]/[blockHeightPx]
     * pair taken from a real canvas, this reduces to exactly [entityOrigin]'s
     * result -- it is a strict generalisation, not a parallel formula.
     *
     * @param base World point [SurfacePicking] would resolve this element's
     *   anchor pixel to -- i.e. [planePoint] at that pixel, ZERO depth (depth
     *   is applied here, not baked into [base]), matching how [pointerEntityAt]
     *   already splits `base` from `depth` for its own single-glyph entity.
     * @param depth World-block offset toward the viewer, already resolved --
     *   same contract as [entityOrigin]'s `depth`.
     */
    private fun elementOrigin(
        base: Vec3d,
        blockWidthPx: Int,
        blockHeightPx: Int,
        scaleX: Float,
        scaleY: Float,
        depth: Float = 0f
    ): Vec3d {
        val unitX = TextMetrics.PIXEL_SIZE * scaleX.toDouble()
        val unitY = TextMetrics.PIXEL_SIZE * scaleY.toDouble()
        val localX = unitX * (1.0 - blockWidthPx / 2.0)
        val localY = unitY * blockHeightPx.toDouble()
        val theta = Math.toRadians(yawDegrees.toDouble())
        val cos = cos(theta)
        val sin = sin(theta)
        return Vec3d(
            base.x - (localX * cos) + depth * sin,
            base.y - localY,
            base.z - (localX * -sin) + depth * cos
        )
    }

    /** Test seam for [elementOrigin] + [planePoint] together, matching how [spriteEntity]/[labelEntity] compose them. */
    internal fun elementOriginForTest(
        px: Int, py: Int,
        blockWidthPx: Int, blockHeightPx: Int,
        scaleX: Float, scaleY: Float,
        depth: Float = 0f
    ): Vec3d = elementOrigin(planePoint(px, py), blockWidthPx, blockHeightPx, scaleX, scaleY, depth)

    /**
     * One text display per depth layer, back to front.
     *
     * Everything inside a single text display is coplanar, so wherever the UI
     * overlaps — an icon on a slot, a slot on the frame — the glyphs z-fight
     * and flicker as the camera moves. Splitting by layer and stepping each
     * one [OVERLAY_Z_STEP] toward the viewer gives the depth buffer something
     * to separate, while staying far too small to read as an air gap.
     *
     * Every layer shares one block size ([SpriteCanvas.blockWidthPx] and
     * [SpriteCanvas.emittedRowCount] are canvas-level, not per-layer), so they
     * all resolve the same [entityOrigin] and stack exactly.
     */
    fun toEntities(): List<VirtualEntity> {
        val entities = when (effectiveRenderMode()) {
            RenderMode.COMPOSITED -> toEntitiesComposited()
            RenderMode.ENTITIES, RenderMode.AUTO -> toEntitiesFlat()
        }
        entities.forEach(::configureMotion)
        return entities
    }

    private fun configureMotion(entity: VirtualEntity) {
        entity.interpolationDuration = motionInterpolationTicks
        entity.teleportDuration = motionInterpolationTicks
        entity.startInterpolation = 0
    }

    /**
     * [RenderMode.COMPOSITED]: one text display per canvas layer, plus any
     * explicitly requested block displays. One allocator orders both media,
     * including the real thickness occupied by a block.
     */
    private fun toEntitiesComposited(): List<VirtualEntity> {
        val layers = canvas.layers()
        val extras = elements
        if (layers.isEmpty() && extras.isEmpty()) return listOf(toEntity())

        val depths = DepthAllocator.allocate(
            layers.map { DepthLayer(canvasLayerAllocationKey(it), 0f) } +
                extras.map { DepthLayer(it.depthKey, it.thickness) },
            separation = LAYER_Z_STEP
        )
        val out = mutableListOf<Pair<Double, VirtualEntity>>()
        layers.forEach { layer ->
            val key = canvasLayerAllocationKey(layer)
            val entity = toEntityAtDepth(
                layer,
                depths.getValue(key) + canvasLayerDepthOffset(layer)
            ).also { it.reconcileKey = "canvas-layer/$layer" }
            out += canvasLayerOrderKey(layer) to entity
        }
        extras.forEach { el ->
            val depth = depths.getValue(el.depthKey)
            val entity = when (el) {
                is EntityElement.SpriteEl -> spriteEntity(el, depth + el.depthOffset)
                is EntityElement.LabelEl -> labelEntity(el, depth + el.depthOffset)
                is EntityElement.BlockEl -> blockEntity(el, depth)
            }.also { it.reconcileKey = el.reconcileKey }
            out += el.depthKey to entity
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** The chrome plane a laminated face layer belongs to. */
    private fun canvasLayerAllocationKey(layer: Int): Double =
        if (layer >= FACE_LAYER_BASE) (layer - FACE_LAYER_BASE).toDouble()
        else layer.toDouble()

    /** Paint coatings after their chrome, but before the next ordinary kind. */
    private fun canvasLayerOrderKey(layer: Int): Double =
        canvasLayerAllocationKey(layer) + if (layer >= FACE_LAYER_BASE) 0.5 else 0.0

    /** Physical separation is local to the coating, not a global layer step. */
    private fun canvasLayerDepthOffset(layer: Int): Float =
        if (layer >= FACE_LAYER_BASE) FACE_COATING_Z_BIAS else 0f

    /**
     * [RenderMode.ENTITIES]: one text display per painted [EntityElement]
     * instead of per depth layer -- see [RenderMode.ENTITIES]'s KDoc.
     *
     * Ordered back-to-front by each element's `depthKey`, but depth is
     * allocated only through elements whose pixel bounds overlap. Disjoint
     * labels and sprites remain exactly coplanar even when their kinds differ;
     * an actual overlap advances only [ENTITY_OVERLAY_Z_BIAS].
     *
     * A global key rank still made a title label, a tab label and a grid icon
     * into parallel sheets despite never touching. The overlap graph is the
     * missing constraint: only an icon over its own slot, text over its own
     * face, or another genuinely intersecting pair needs depth ordering.
     */
    private fun toEntitiesFlat(): List<VirtualEntity> {
        val ordered = elements.sortedBy { it.depthKey }
        val depths = allocateLocalEntityDepths(ordered)
        return ordered.map { el ->
            val depth = depths.getValue(el)
            val entity = when (el) {
                is EntityElement.SpriteEl -> spriteEntity(el, depth + el.depthOffset)
                is EntityElement.LabelEl -> labelEntity(el, depth + el.depthOffset)
                is EntityElement.BlockEl -> blockEntity(el, depth)
            }
            entity.reconcileKey = el.reconcileKey
            entity
        }
    }

    /** Allocate depth through the local overlap graph, one conceptual key at a time. */
    private fun allocateLocalEntityDepths(
        ordered: List<EntityElement>
    ): Map<EntityElement, Float> {
        data class Placed(val element: EntityElement, val depth: Float)

        val result = HashMap<EntityElement, Float>(ordered.size)
        val placed = mutableListOf<Placed>()
        for (group in ordered.groupBy { it.depthKey }.values) {
            val groupDepths = group.associateWith { element ->
                placed.asSequence()
                    .filter { overlaps(elementBounds(it.element), elementBounds(element)) }
                    .maxOfOrNull { renderedFront(it.element, it.depth) + ENTITY_OVERLAY_Z_BIAS }
                    ?: 0f
            }
            groupDepths.forEach { (element, depth) ->
                result[element] = depth
                placed += Placed(element, depth)
            }
        }
        return result
    }

    private fun elementBounds(element: EntityElement): Rect = when (element) {
        is EntityElement.SpriteEl -> element.rect
        is EntityElement.BlockEl -> element.rect
        is EntityElement.LabelEl -> Rect(
            element.x,
            element.y,
            TextMetrics.textWidthPx(element.text),
            TextMetrics.lineCount(element.text) * TextMetrics.FONT_LINE_HEIGHT_PX
        )
    }

    private fun overlaps(a: Rect, b: Rect): Boolean =
        a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom

    private fun renderedFront(element: EntityElement, depth: Float): Float = when (element) {
        is EntityElement.BlockEl -> when {
            element.behindLayer -> depth
            // A local extrusion (for example a button body) deliberately
            // does not reserve depth for unrelated content that happens to
            // cross its rect. Its own face is placed with an explicit
            // depthOffset. Only a true blockPanel advances later layers.
            else -> depth + element.thickness
        }
        // Explicit offsets are local coatings (not structural thickness).
        // A raised button face must not lift a later window-frame strip that
        // merely crosses the same pixels, or that strip becomes a detached
        // sheet at the button's full height.
        is EntityElement.SpriteEl -> depth
        is EntityElement.LabelEl -> depth
    }

    /**
     * One [RenderMode.ENTITIES] sprite entity: vanilla's native atlas-sprite
     * text-component content ([TextComponent.sprite]), a client-fixed 8x8
     * quad, stretched to [EntityElement.SpriteEl.rect] with a NON-UNIFORM
     * scale so the sprite's real aspect ratio survives rather than being
     * squashed into a square. See [elementOrigin] for why the anchor maths
     * cannot reuse [entityOrigin] unchanged once the two axes scale
     * differently.
     */
    private fun spriteEntity(el: EntityElement.SpriteEl, depth: Float): VirtualTextDisplay {
        val base = planePoint(el.rect.x, el.rect.y)
        val sx = pixelScale * (el.rect.w / ATLAS_SPRITE_GLYPH_PX)
        val sy = pixelScale * (el.rect.h / ATLAS_SPRITE_GLYPH_PX)
        val pos = elementOrigin(
            base = base,
            blockWidthPx = ATLAS_SPRITE_GLYPH_PX.toInt(),
            // The FULL line pitch, with no -1.
            //
            // The -1 here is the client's own `lines * pitch - 1` convention
            // for a block of TEXT, and it does not apply to an atlas sprite:
            // this glyph is a square 8x8 object, not a line with descender
            // space. Being one glyph pixel out is then multiplied by the
            // element's own scale (sy = pixelScale * rect.h / 8), so the error
            // grew with the sprite -- 1px on an 8px icon, 4px on a 32px one,
            // every sprite in every ENTITIES-mode surface sitting h/8 canvas
            // pixels high.
            //
            // Measured, not reasoned. `/dk calib <i> [nopack]` draws one
            // sprite twice against the backing slab, which is built from the
            // canvas bounds and touches no glyph metrics -- the only
            // reference independent of both paths being compared. The
            // measurement is linear in this constant at h/8 canvas pixels per
            // glyph pixel, which is what identified 10 rather than 9 or 8.
            // See CalibrationWindow.
            blockHeightPx = TextMetrics.FONT_LINE_HEIGHT_PX,
            scaleX = sx,
            scaleY = sy,
            depth = depth
        )
        return VirtualTextDisplay().also { d ->
            d.position = pos
            d.billboard = orientation
            d.backgroundColor = DkColor.TRANSPARENT
            d.brightness = Brightness.FULL
            d.hasShadow = false
            d.textAlignment = TextAlignment.LEFT
            d.lineWidth = ATLAS_SPRITE_GLYPH_PX.toInt() + LINE_WIDTH_MARGIN_PX
            d.transformation = Mat4f(
                Matrix4f()
                    .rotateY(Math.toRadians(yawDegrees.toDouble()).toFloat())
                    .scale(sx, sy, pixelScale)
            )
            val sprite = TextComponent.sprite(el.entry.id.atlas, el.entry.id.sprite)
            d.text = if (el.tint != null) sprite.withColor(el.tint) else sprite
        }
    }

    /** One [RenderMode.ENTITIES] label entity: one line of plain vanilla text, no pack required. */
    private fun labelEntity(el: EntityElement.LabelEl, depth: Float): VirtualTextDisplay {
        val base = planePoint(el.x, el.y)
        val blockW = TextMetrics.textWidthPx(el.text)
        val blockH = TextMetrics.lineCount(el.text) * TextMetrics.FONT_LINE_HEIGHT_PX - 1
        val s = pixelScale
        val pos = elementOrigin(base, blockWidthPx = blockW, blockHeightPx = blockH, scaleX = s, scaleY = s, depth = depth)
        return VirtualTextDisplay().also { d ->
            d.position = pos
            d.billboard = orientation
            d.backgroundColor = DkColor.TRANSPARENT
            d.brightness = Brightness.FULL
            d.hasShadow = false
            d.textAlignment = TextAlignment.LEFT
            d.lineWidth = blockW + LINE_WIDTH_MARGIN_PX
            d.transformation = Mat4f(
                Matrix4f().rotateY(Math.toRadians(yawDegrees.toDouble()).toFloat()).scale(s, s, s)
            )
            val text = TextComponent.of(el.text)
            d.text = if (el.color != null) text.withColor(el.color) else text
        }
    }

    /**
     * One [RenderMode.ENTITIES] block element: a real block occupying real
     * volume inside the panel.
     *
     * Unlike the sprite and label paths this is NOT a flat quad, which is the
     * entire point -- a block display is world-lit, carries a real material
     * and has genuine depth. [DepthAllocator] has already reserved
     * [EntityElement.BlockEl.thickness] of space for a proud panel. For a
     * backing block, [depth] is instead its front face and the physical volume
     * grows behind it without advancing unrelated layers.
     *
     * The block is scaled to the element's pixel rect on the panel's two
     * in-plane axes and to its real thickness on the third, so it lines up
     * with the sprites and text around it while still being a solid object.
     */
    private fun blockEntity(el: EntityElement.BlockEl, depth: Float): VirtualBlockDisplay {
        val base = planePoint(el.rect.x, el.rect.y)
        // pixelScale scales an 8px text glyph. A canvas pixel is only
        // PIXEL_SIZE (1/8) of that unit; omitting this factor makes every
        // block panel eight times wider and taller than its requested rect.
        val unit = TextMetrics.PIXEL_SIZE * pixelScale
        val blockDepth = if (el.behindLayer) {
            depth - el.physicalThickness - FACE_COATING_Z_BIAS
        } else {
            depth
        }
        return VirtualBlockDisplay().also { d ->
            d.blockState = el.block
            d.position = base
            d.billboard = orientation
            // World-lit would be the honest choice for a real block, but a
            // panel's elements must read consistently against sprite glyphs
            // beside them, which are always full-bright. A per-element
            // override is the open question recorded in the compositor design.
            d.brightness = Brightness.FULL
            d.transformation = Mat4f(
                Matrix4f()
                    .rotateY(Math.toRadians(yawDegrees.toDouble()).toFloat())
                    // Canvas +Y runs downward, so the block hangs down from
                    // its top-left corner, matching planePoint's convention.
                    .translate(0f, -(unit * el.rect.h), blockDepth)
                    .scale(unit * el.rect.w, unit * el.rect.h, el.physicalThickness)
            )
        }
    }

    @JvmOverloads
    fun toEntity(layer: Int? = null, depthIndex: Int = 0): VirtualTextDisplay =
        toEntityAtDepth(layer, depthIndex * LAYER_Z_STEP)

    private fun toEntityAtDepth(layer: Int?, depth: Float): VirtualTextDisplay = VirtualTextDisplay().also { d ->
        d.position = entityOrigin(depth)
        d.billboard = orientation
        // ONLY the bottom layer. A text display paints its background across
        // the whole measured block, so giving every layer one stacks N opaque
        // quads and each hides the glyphs of the layer beneath it -- which
        // reads exactly like z-fighting but is pure occlusion.
        val isBottomLayer = layer == null || layer == canvas.layers().firstOrNull()
        // A backing block already paints an opaque panel behind everything, so
        // the backdrop would only add a translucent quad a few millimetres in
        // front of it. Translucent geometry does not write depth, so stacking
        // it against the slab is exactly the sorting mess it looks like.
        val wantsBackdrop = isBottomLayer && backingBlock == null
        d.backgroundColor = if (wantsBackdrop) backdrop ?: DkColor.TRANSPARENT else DkColor.TRANSPARENT
        d.brightness = Brightness.FULL
        d.hasShadow = false
        val s = pixelScale
        val yawRadians = Math.toRadians(yawDegrees.toDouble()).toFloat()
        d.transformation = Mat4f(Matrix4f().rotateY(yawRadians).scale(s, s, s))

        // The canvas model assumes every row starts at x=0 and that no row
        // ever wraps onto a second display line — neither holds under
        // VirtualTextDisplay's defaults. CENTER centres each line
        // independently, sliding rows of different widths sideways relative
        // to each other; LEFT keeps every row's x=0 aligned with the
        // entity's own origin, matching the canvas.
        d.textAlignment = TextAlignment.LEFT

        // lineWidth defaults to 200, and the client wraps any row wider than
        // that (DisplayRenderer$TextDisplayRenderer.splitLines) — pushing
        // everything below it down by however much the wrapped content
        // added. Deriving lineWidth from the canvas's own widest row (with a
        // small margin) makes wrapping impossible regardless of content.
        d.lineWidth = canvas.blockWidthPx() + LINE_WIDTH_MARGIN_PX

        // Surfaces are glyph-composed, so with no slice source installed a
        // canvas that actually needs glyphs (sprites, slices, or spacing)
        // would render as a field of missing-glyph boxes. Say so in words
        // instead — a legible message beats tofu. Plain text needs no pack at
        // all, so it must never be discarded here (see requiresPack()).
        d.text = if (SliceGlyphSource.installed == null) {
            if (canvas.requiresPack()) {
                SpriteDiagnostics.packDisabled()
                TextComponent.of("[DisplayKit surface unavailable: resource pack disabled]")
            } else {
                // Anchor padding is written in the spacing font, so emitting it
                // without a pack would turn legible text into tofu. The block
                // is then measured from the content and entityOrigin's size
                // assumption no longer holds -- acceptable, because this is
                // already the degraded path.
                canvas.toTextComponent(anchor = false, layer = layer)
            }
        } else {
            canvas.toTextComponent(layer = layer)
        }
    }

    /**
     * A one-glyph entity showing the cursor at canvas ([px], [py]), or null if
     * the sprite is unavailable.
     *
     * Its own entity on purpose. Painting the cursor into the canvas would
     * repaint every layer each tick — seven layers at 20tps is ~140 metadata
     * packets a second per viewer, for a crosshair. As an entity it is one
     * teleport.
     *
     * Deviation from the brief's sketch: the glyph is composited on a canvas
     * sized to [SpriteEntry.glyphAdvance] x [SpriteEntry.height] rather than
     * the full surface canvas. A canvas built at the surface's own
     * `widthPx`/`heightPx` measures its block at that same fixed width
     * regardless of where the glyph is drawn on it ([SpriteCanvas.blockWidthPx]
     * floors at `widthPx`), so [entityOrigin] would always resolve the same
     * `position` no matter what ([px], [py]) was passed in — the crosshair
     * would only ever move via glyph-advance codepoints inside one static
     * text block, never by moving the entity. That defeats the entity-teleport
     * design this whole primitive exists for (see the type doc above). Sizing
     * the canvas to the glyph's own row advance — not [SpriteEntry.width],
     * which for a trimmed sprite like the bundled crosshair overstates what
     * the client actually measures — makes its block genuinely tiny AND
     * exact, and [planePoint] + [entityOrigin]'s `base` parameter place that
     * tiny block directly at the aimed-at point on the surface's plane, so
     * `showPointer` can move the cursor with a real position change.
     */
    fun pointerEntityAt(px: Int, py: Int): VirtualTextDisplay? {
        val id = pointerSpriteOverride ?: POINTER_SPRITE
        val entry = SpriteIndex.bundled.get(id) ?: run {
            SpriteDiagnostics.warnOnce(
                "pointer-sprite-missing:$id",
                "Pointer sprite $id is not in the sprite index; surfaces will " +
                    "render without a cursor."
            )
            return null
        }
        // Centre the crosshair on the aimed-at point rather than hanging it
        // down-right of it. NOT clamped to the canvas: a cursor dragged back
        // inside near an edge stops tracking exactly where precision matters
        // most, and the corner is where the close button lives.
        val cx = px - entry.width / 2
        val cy = py - entry.height / 2

        // One step in front of the frontmost content layer. This must use the
        // same mixed-media allocation as toEntities(): a block button can be
        // thicker than several ordinary layer gaps, so counting canvas layers
        // alone would leave the pointer embedded inside the button slab.
        val blocks = elements.filterIsInstance<EntityElement.BlockEl>()
        val depth = if (blocks.isEmpty()) {
            // Preserve the original flat-surface placement exactly. In
            // ENTITIES mode canvas.layers() is empty, which deliberately puts
            // a pointer on the same plane as an ordinary standalone icon.
            canvas.layers().size * LAYER_Z_STEP
        } else {
            val depthLayers = if (effectiveRenderMode() == RenderMode.COMPOSITED) {
                canvas.layers().map { DepthLayer(it.toDouble(), 0f) } +
                    blocks.map { DepthLayer(it.depthKey, it.thickness) }
            } else {
                elements.map { DepthLayer(it.depthKey, it.thickness) }
            }
            DepthAllocator.totalDepth(depthLayers, LAYER_Z_STEP) + LAYER_Z_STEP
        }

        // ONE path, both render modes.
        //
        // The pointer is its own entity by design -- painting it into the
        // canvas would repaint every layer each tick, ~140 metadata packets a
        // second for a cursor -- so it never needed the shared canvas at all.
        // It nonetheless used to build a SpriteCanvas, the pack-backed
        // bitmap-glyph path, which anchors differently from the atlas-sprite
        // entity an ordinary icon() emits. The result was a cursor that did
        // not sit where it said it did, and differently per mode: measured at
        // 9.75 canvas pixels above the ray hit under COMPOSITED, and under
        // ENTITIES not drawn at all, because that path needs slice glyphs a
        // no-pack client never receives.
        //
        // Going through spriteEntity keeps the cursor honest by construction:
        // it is placed by exactly the code measured against the backing slab
        // in CalibrationWindow, and there is no second convention left to
        // drift from. PointerAcrossModesTest holds the two modes together.
        return spriteEntity(
            EntityElement.SpriteEl(
                entry, Rect(cx, cy, entry.width, entry.height), null, 0.0
            ),
            depth
        ).also(::configureMotion)
    }

    private inner class Painter : SurfacePainter {

        /**
         * How far the current widget is raised above the surface's base plane.
         *
         * Layering by primitive KIND alone is not enough: a window frame and
         * the title bar drawn on top of it are both chrome, so they land on
         * the same plane and z-fight exactly like unlayered glyphs did. Widget
         * helpers raise this for their own body (see [elevate]), which is what
         * separates a tab from the frame it sits on, or a scroll thumb from
         * its track.
         */
        private var elevation = 0

        /** Resolved once per paint, not per call -- see [Surface.effectiveRenderMode]. */
        private val mode = effectiveRenderMode()

        private fun depth(kind: Int) = elevation * KINDS_PER_ELEVATION + kind

        /**
         * A second text display laminated onto this elevation's chrome.
         * Keeping the ordinary chrome key encoded in the layer id lets both
         * displays share identical canvas bounds and entity origins.
         */
        private fun faceLayer() = FACE_LAYER_BASE + depth(KIND_CHROME)

        override fun identity(key: String, block: SurfacePainter.() -> Unit) {
            val previousIdentity = paintIdentity
            val previousOrdinal = paintIdentityOrdinal
            paintIdentity = key
            paintIdentityOrdinal = 0
            try {
                block()
            } finally {
                paintIdentity = previousIdentity
                paintIdentityOrdinal = previousOrdinal
            }
        }

        override fun elevate(delta: Int, block: SurfacePainter.() -> Unit) {
            val previous = elevation
            elevation += delta
            try {
                block()
            } finally {
                elevation = previous
            }
        }

        override fun frame(entry: SpriteEntry, rect: Rect, tint: DkColor?, depthOffset: Float) {
            if (mode == RenderMode.ENTITIES || depthOffset != 0f) {
                recordFrame(entry, rect, tint, depth(KIND_CHROME).toDouble(), depthOffset)
                return
            }
            canvas.currentLayer = depth(KIND_CHROME)
            NineSlicePainter.paint(canvas, entry, rect, tint)
        }

        override fun fill(color: DkColor, rect: Rect) {
            val e = resolveSprite(FILL_SPRITE, "a colour fill") ?: return
            if (mode == RenderMode.ENTITIES || rect.w < e.width || rect.h < e.height) {
                // Stretched, not tiled: one entity for the whole rect. Tiling
                // exists only to keep a bitmap glyph's own pixels crisp under
                // the composited canvas; a fill sprite is uniform colour, so
                // stretching it is visually identical and costs one entity
                // instead of a grid of them. The same fallback is deliberately
                // used for sub-glyph rectangles in composited mode. Thin
                // dividers, progress fills and row highlights are valid UI;
                // callers must never need to know the source glyph is 16 px.
                recordElement(EntityElement.SpriteEl(e, rect, color, depth(KIND_CHROME).toDouble()))
                return
            }
            canvas.currentLayer = depth(KIND_CHROME)
            // A fill lands wherever its rect does, and a glyph's ascent is
            // baked per y phase -- so a panel that moves two pixels mints a
            // new variant, rebuilds the pack, and costs every connected
            // client a re-download. There are only ever a fixed handful of
            // phases, so warm them all here rather than asking every caller
            // to predict the y values its layout will produce. Idempotent
            // and a few map lookups; clicking a picker tab used to cost a
            // full pack rebuild for want of this.
            require(e.width > 0 && e.height > 0) {
                "Fill sprite $FILL_SPRITE has non-positive dimensions " +
                    "(${e.width}x${e.height})."
            }
            SpriteGlyphs.warmAllPhases(e)
            for (y in tileSteps(rect.y, rect.bottom, e.height)) {
                for (x in tileSteps(rect.x, rect.right, e.width)) {
                    canvas.draw(e, x, y, color)
                }
            }
        }

        override fun iconFitted(
            entry: SpriteEntry,
            x: Int,
            y: Int,
            boxW: Int,
            boxH: Int,
            tint: DkColor?
        ): Pair<Int, Int> {
            // Centred in the box so a wide sprite and a tall one both sit in
            // the middle of their cell rather than hugging its corner.
            // Shared with pre-warming via SpriteFit: a caller that warms this
            // glyph must land on the identical y, or the ascent differs and
            // the warm-up misses.
            val h = SpriteFit.height(entry, boxW, boxH)
            val w = entry.scaledWidth(h)
            val (rx, ry) = SpriteFit.origin(entry, x, y, boxW, boxH)
            if (mode == RenderMode.ENTITIES) {
                recordElement(EntityElement.SpriteEl(entry, Rect(rx, ry, w, h), tint, depth(KIND_ICON).toDouble()))
                return w to h
            }
            canvas.currentLayer = depth(KIND_ICON)
            canvas.draw(entry, rx, ry, tint, renderHeight = h)
            return w to h
        }

        override fun chromeIconFitted(
            entry: SpriteEntry,
            x: Int,
            y: Int,
            boxW: Int,
            boxH: Int,
            tint: DkColor?
        ): Pair<Int, Int> {
            val h = SpriteFit.height(entry, boxW, boxH)
            val w = entry.scaledWidth(h)
            val (rx, ry) = SpriteFit.origin(entry, x, y, boxW, boxH)
            if (mode == RenderMode.ENTITIES) {
                recordElement(EntityElement.SpriteEl(
                    entry,
                    Rect(rx, ry, w, h),
                    tint,
                    depth(KIND_CHROME).toDouble()
                ))
                return w to h
            }
            canvas.currentLayer = depth(KIND_CHROME)
            canvas.draw(entry, rx, ry, tint, renderHeight = h)
            return w to h
        }

        override fun icon(entry: SpriteEntry, x: Int, y: Int, tint: DkColor?) {
            if (mode == RenderMode.ENTITIES) {
                recordElement(EntityElement.SpriteEl(entry, Rect(x, y, entry.width, entry.height), tint, depth(KIND_ICON).toDouble()))
                return
            }
            canvas.currentLayer = depth(KIND_ICON)
            canvas.draw(entry, x, y, tint)
        }

        override fun faceIcon(entry: SpriteEntry, x: Int, y: Int, tint: DkColor?, depthOffset: Float) {
            if (mode == RenderMode.COMPOSITED && depthOffset == 0f) {
                canvas.currentLayer = faceLayer()
                canvas.draw(entry, x, y, tint)
                return
            }
            recordElement(EntityElement.SpriteEl(
                entry,
                Rect(x, y, entry.width, entry.height),
                tint,
                depth(KIND_CHROME).toDouble(),
                depthOffset = depthOffset + FACE_COATING_Z_BIAS
            ))
        }

        override fun label(text: String, x: Int, y: Int, color: DkColor?) {
            if (mode == RenderMode.ENTITIES) {
                recordElement(EntityElement.LabelEl(text, x, y, color, depth(KIND_TEXT).toDouble()))
                return
            }
            canvas.currentLayer = depth(KIND_TEXT)
            canvas.text(text, x, y, color)
        }

        override fun faceLabel(text: String, x: Int, y: Int, color: DkColor?, depthOffset: Float) {
            if (mode == RenderMode.COMPOSITED && depthOffset == 0f) {
                canvas.currentLayer = faceLayer()
                canvas.text(
                    text,
                    x,
                    y - TextMetrics.TEXT_ROW_ANCHOR_OFFSET_PX,
                    color
                )
                return
            }
            recordElement(EntityElement.LabelEl(
                text, x, y, color, depth(KIND_CHROME).toDouble(),
                depthOffset = depthOffset + FACE_COATING_Z_BIAS
            ))
        }

        override fun blockPanel(block: BlockStateRef, rect: Rect, thickness: Float) {
            require(thickness > 0f) {
                "A block panel must have positive thickness (got $thickness). Use fill() for a flat rectangle."
            }
            recordElement(EntityElement.BlockEl(
                block, rect, thickness, behindLayer = false, reserveThickness = true,
                depthKey = depth(KIND_CHROME).toDouble()
            ))
        }

        override fun blockBacking(block: BlockStateRef, rect: Rect, thickness: Float) {
            require(thickness > 0f) {
                "A block backing must have positive thickness (got $thickness). Use fill() for a flat rectangle."
            }
            recordElement(EntityElement.BlockEl(
                block, rect, thickness, behindLayer = true, reserveThickness = false,
                depthKey = depth(KIND_CHROME).toDouble()
            ))
        }

        override fun blockExtrusion(block: BlockStateRef, rect: Rect, thickness: Float) {
            require(thickness > 0f) {
                "A block extrusion must have positive thickness (got $thickness)."
            }
            recordElement(EntityElement.BlockEl(
                block, rect, thickness, behindLayer = false, reserveThickness = false,
                depthKey = depth(KIND_CHROME).toDouble()
            ))
        }

        override fun slot(x: Int, y: Int, item: ItemRef?) {
            val e = resolveSprite(SLOT_SPRITE, "a slot") ?: return
            if (mode == RenderMode.ENTITIES) {
                recordElement(EntityElement.SpriteEl(e, Rect(x, y, e.width, e.height), null, depth(KIND_SLOT).toDouble()))
            } else {
                canvas.currentLayer = depth(KIND_SLOT)
                canvas.draw(e, x, y)
            }
            if (item != null) slots += Rect(x, y, 18, 18) to item
        }

        override fun region(id: String, rect: Rect, onClick: () -> Unit) {
            require(orientation == Billboard.FIXED) {
                "Interactive surfaces must use Billboard.FIXED. A billboarded plane " +
                    "rotates per viewer, so its orientation is not knowable server-side " +
                    "and hit-testing would mis-aim."
            }
            require(rect.right <= widthPx && rect.bottom <= heightPx && rect.x >= 0 && rect.y >= 0) {
                "Region '$id' $rect falls outside the ${widthPx}x$heightPx surface."
            }
            rects += HitRect(id, rect, onClick)
        }
    }

    /**
     * [RenderMode.ENTITIES] rendering of [SurfacePainter.frame].
     *
     * True nine-slice needs a CROP of the source texture, and vanilla's
     * atlas-sprite glyph has no sub-region mechanism -- it always draws the
     * WHOLE sprite (see [RenderMode.ENTITIES]'s KDoc). Cropping is recovered
     * by POSITION and OCCLUSION instead of pixels:
     *
     * 1. The frame sprite is drawn at its native size, once per corner, each
     *    anchored so that corner's TRUE nine-slice corner (the sprite's own
     *    `left x top` block, etc. -- [SpriteEntry.nineSlice]) lands exactly
     *    on the target rect's corner. The rest of that native-size copy --
     *    the part that is really centre/edge art, not corner art -- spills
     *    INWARD, over the window's interior.
     * 2. A tinted fill sprite is drawn microscopically IN FRONT of the
     *    corners while retaining the same conceptual depth key, inset by the
     *    nine-slice borders on all four
     *    sides PLUS flat border strips between the corners. Between them
     *    they cover exactly the area the corners spilled over, hiding it.
     *
     * Because every corner is anchored at the corner it belongs to and
     * grows inward, nothing ever spills OUTSIDE the window -- only over
     * area this function immediately re-covers.
     *
     * The border strips are a flat fill in the tint colour, not the sprite's
     * real edge art -- exact for a uniform-band sprite like
     * `gui:tooltip/background`, an approximation for one with a patterned
     * edge. A patterned edge would need its own tiled, overlap-hiding
     * copies (the same trick as the corners, repeated along each edge) and
     * that is deliberately NOT implemented here.
     *
     * When [tint] is null -- the common case, since [COMPOSITED] callers like
     * `PickerWindow` correctly leave colour to [NineSlicePainter] reading the
     * frame texture itself -- the fill would otherwise render as
     * [FILL_SPRITE]'s own raw white. Falling back to [SpriteEntry.averageColor]
     * instead makes the substitute read as the same material as the corners
     * it sits between, rather than a blank white panel. The CORNERS are real
     * art and are never tinted with it -- only the fill substitutes are.
     *
     * Falls back to a single tinted whole-sprite stretch -- no corners
     * recovered at all -- when the sprite carries no [SpriteEntry.nineSlice]
     * metadata, or when [rect] is smaller than the sprite's own native size
     * (too small for all four corners to be placed without overlapping each
     * other, the same minimum [NineSliceLayout] imposes on the composited
     * path).
     */
    private fun recordFrame(
        entry: SpriteEntry,
        rect: Rect,
        tint: DkColor?,
        baseKey: Double,
        depthOffset: Float = 0f
    ) {
        val slice = entry.nineSlice
        if (slice == null || rect.w < entry.width || rect.h < entry.height) {
            recordElement(EntityElement.SpriteEl(entry, rect, tint, baseKey, depthOffset))
            return
        }
        // At exactly native size there is nothing to stretch, so slicing is
        // pure loss: it spends nine elements reproducing what one draw already
        // renders perfectly, and each flat fill discards the real pixels it
        // stands in for. `gui/widget/tab_selected` is drawn at its native
        // 130x24 and its centre is entirely transparent, so the substitute
        // fill painted an opaque slab over a frame vanilla leaves hollow --
        // the selected tab came out a solid white box with its label buried.
        if (rect.w == entry.width && rect.h == entry.height) {
            recordElement(EntityElement.SpriteEl(entry, rect, tint, baseKey, depthOffset))
            return
        }
        val l = slice.left; val t = slice.top; val r = slice.right; val b = slice.bottom

        // Corners: native size, anchored so their spill runs inward, never
        // outside the rect.
        recordElement(EntityElement.SpriteEl(entry, Rect(rect.x, rect.y, entry.width, entry.height), tint, baseKey, depthOffset))
        recordElement(EntityElement.SpriteEl(
            entry, Rect(rect.right - entry.width, rect.y, entry.width, entry.height), tint, baseKey, depthOffset
        ))
        recordElement(EntityElement.SpriteEl(
            entry, Rect(rect.x, rect.bottom - entry.height, entry.width, entry.height), tint, baseKey, depthOffset
        ))
        recordElement(EntityElement.SpriteEl(
            entry, Rect(rect.right - entry.width, rect.bottom - entry.height, entry.width, entry.height), tint, baseKey, depthOffset
        ))

        // Background + edge strips occlude the inward spill. They are a local
        // coating, not a structural layer: assigning baseKey + 0.5 made the
        // composited allocator spend a full LAYER_Z_STEP and put a stretched
        // button's fill in front of its own label. Keep the conceptual key
        // and express only the microscopic ordering this assembly needs.
        val fillDepthOffset = depthOffset + ENTITY_OVERLAY_Z_BIAS
        val fill = resolveSprite(FILL_SPRITE, "a frame background") ?: return
        // A caller-supplied tint always wins; otherwise fall back to the real
        // frame's own average colour so the flat fill reads as the same
        // material as the corners rather than FILL_SPRITE's raw white.
        //
        // A null averageColor means the measured region has no opaque pixels
        // at all, so there is no colour to stand in for and an opaque slab is
        // strictly worse than nothing. The corners still spill inward
        // un-occluded, but a hollow frame's corners are mostly transparent
        // too, so what shows through is the frame -- which is what vanilla
        // draws. See SpriteEntry.averageColor.
        val fillTint = tint ?: entry.averageColor?.let { c ->
            DkColor(255, (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        } ?: return
        val innerW = rect.w - l - r
        val innerH = rect.h - t - b
        if (innerW > 0 && innerH > 0) {
            recordElement(EntityElement.SpriteEl(fill, Rect(rect.x + l, rect.y + t, innerW, innerH), fillTint, baseKey, fillDepthOffset))
        }
        if (innerW > 0 && t > 0) recordElement(EntityElement.SpriteEl(fill, Rect(rect.x + l, rect.y, innerW, t), fillTint, baseKey, fillDepthOffset))
        if (innerW > 0 && b > 0) {
            recordElement(EntityElement.SpriteEl(fill, Rect(rect.x + l, rect.bottom - b, innerW, b), fillTint, baseKey, fillDepthOffset))
        }
        if (innerH > 0 && l > 0) recordElement(EntityElement.SpriteEl(fill, Rect(rect.x, rect.y + t, l, innerH), fillTint, baseKey, fillDepthOffset))
        if (innerH > 0 && r > 0) {
            recordElement(EntityElement.SpriteEl(fill, Rect(rect.right - r, rect.y + t, r, innerH), fillTint, baseKey, fillDepthOffset))
        }
    }
}

/**
 * One call to a [SurfacePainter] method, recorded instead of drawn, for
 * [RenderMode.ENTITIES]. [Surface.toEntitiesFlat] turns each of these into
 * its own entity; [Surface.recordFrame] is the one call site that emits
 * several from a single painter call.
 *
 * @param depthKey Back-to-front sort key -- see [Surface.toEntitiesFlat].
 *   A [Double], not the [Int] depth [Surface.KIND_CHROME] etc. use for
 *   [RenderMode.COMPOSITED]'s canvas layers, because [Surface.recordFrame]
 *   needs a background to sit strictly BETWEEN two elements that would
 *   otherwise share one integer kind.
 */
private sealed class EntityElement(val depthKey: Double) {
    var reconcileKey: String? = null
    /**
     * Depth this element physically occupies, in blocks.
     *
     * Zero for anything flat. Only a medium with real volume overrides it,
     * and getting it wrong puts the next layer forward INSIDE this one --
     * see [io.schemat.displaykit.composite.DepthAllocator].
     */
    open val thickness: Float get() = 0f

    class SpriteEl(
        val entry: SpriteEntry,
        val rect: Rect,
        val tint: DkColor?,
        depthKey: Double,
        val depthOffset: Float = 0f
    ) : EntityElement(depthKey)

    class LabelEl(
        val text: String,
        val x: Int,
        val y: Int,
        val color: DkColor?,
        depthKey: Double,
        val depthOffset: Float = 0f
    ) : EntityElement(depthKey)

    /**
     * A real block, occupying real volume inside the panel.
     *
     * The first element type that is not a flat plane, and the reason
     * [thickness] exists at all. A block display is lit by the world, carries
     * a real material and has genuine depth -- things a sprite cannot do --
     * so it is a capability rather than a fallback, and it composes with
     * sprites and text in one surface rather than replacing them.
     */
    class BlockEl(
        val block: BlockStateRef,
        val rect: Rect,
        val physicalThickness: Float,
        val behindLayer: Boolean,
        val reserveThickness: Boolean,
        depthKey: Double
    ) : EntityElement(depthKey) {
        override val thickness: Float = if (reserveThickness) physicalThickness else 0f

        init {
            require(physicalThickness > 0f) {
                "a block element must have real depth, got $physicalThickness -- " +
                    "use a sprite fill for a flat rectangle"
            }
        }
    }
}

/**
 * Resolve a sprite id against the bundled index, warning once and returning
 * null when it does not resolve.
 *
 * The single implementation of the spec's unknown-sprite rule. Every part that
 * looks a sprite up goes through here, because the alternative — `get(id)?.let
 * { … }` at each site — skipped the visual while the rest of the part carried
 * on, producing an invisible control that was still clickable. Callers must
 * skip the WHOLE part on null, not just its picture.
 *
 * @param part What was being drawn, for the log line.
 */
internal fun resolveSprite(id: SpriteId, part: String): SpriteEntry? {
    val entry = SpriteIndex.bundled.get(id)
    if (entry == null) {
        SpriteDiagnostics.warnOnce(
            "unknown-sprite:$id",
            "Sprite $id does not resolve in the bundled index, so $part cannot be drawn " +
                "and is skipped. Check the id, or re-run " +
                ":libs:displaykit:pack:generateSpriteIndex if this sprite was added in a " +
                "newer Minecraft version."
        )
    }
    return entry
}

/**
 * Tile positions along one axis, covering [start] until [end]: a full grid of
 * [tile]-sized steps, with the final tile placed flush against [end] so it
 * overlaps its neighbour rather than overflowing past it. The same trick
 * [NineSliceLayout] uses for its borders — invisible on a uniform tile, and
 * exact everywhere else. Requires at least one whole tile to fit (checked by
 * callers before this runs).
 */
private fun tileSteps(start: Int, end: Int, tile: Int): List<Int> {
    val positions = ArrayList<Int>()
    var p = start
    while (p + tile < end) {
        positions.add(p)
        p += tile
    }
    positions.add(end - tile)
    return positions
}
