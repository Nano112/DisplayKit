package io.schemat.displaykit.surface

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
import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.sprite.SpriteCanvas
import io.schemat.displaykit.sprite.SpriteDiagnostics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
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
    fun frame(entry: SpriteEntry, rect: Rect, tint: DkColor? = null)
    fun fill(color: DkColor, rect: Rect)
    fun icon(entry: SpriteEntry, x: Int, y: Int, tint: DkColor? = null)
    fun label(text: String, x: Int, y: Int, color: DkColor? = null)
    fun slot(x: Int, y: Int, item: ItemRef? = null)
    fun region(id: String, rect: Rect, onClick: () -> Unit)
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
    val heightPx: Int,
    var position: Vec3d,
    var targetWidthBlocks: Float,
    val orientation: Billboard = Billboard.FIXED
) {
    companion object {
        /** The single depth step for anything that must sit in front of the plane. */
        const val OVERLAY_Z_STEP = 0.005f

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
    }

    init {
        require(widthPx > 0) { "Surface widthPx must be positive (got $widthPx)." }
        require(heightPx > 0) { "Surface heightPx must be positive (got $heightPx)." }
        require(targetWidthBlocks > 0) {
            "Surface targetWidthBlocks must be positive (got $targetWidthBlocks)."
        }
    }

    /**
     * Optional solid panel behind the whole composition. Null (the default)
     * keeps the previous fully transparent background.
     *
     * One field and zero extra entities, versus a second stretched entity
     * that would cost an entity and need its own sizing.
     *
     * [DkColor]'s first parameter is alpha; 100-149 and 200-249 are reserved
     * shader sentinels ([DkColor.withGlass] / [DkColor.withCornerRadius]) —
     * pick a value outside both bands.
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
     */
    fun toBackingEntity(): VirtualBlockDisplay? {
        val block = backingBlock ?: return null
        return VirtualBlockDisplay().also { d ->
            d.blockState = block
            d.position = position
            d.billboard = orientation
            d.brightness = Brightness.FULL
            val w = widthPx * pixelScale * TextMetrics.PIXEL_SIZE
            val h = heightPx * pixelScale * TextMetrics.PIXEL_SIZE
            d.transformation = Mat4f(
                Matrix4f()
                    .rotateY(Math.toRadians(yawDegrees.toDouble()).toFloat())
                    .translate(0f, -h, -(OVERLAY_Z_STEP + backingThicknessBlocks))
                    .scale(w, h, backingThicknessBlocks)
            )
        }
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

    fun canvasItemCount(): Int = canvas.itemCount()
    fun canvasItemPositions(): List<Pair<Int, Int>> = canvas.itemPositions()
    fun hitRects(): List<HitRect> = rects.toList()
    fun slotItems(): List<Pair<Rect, ItemRef>> = slots.toList()

    /** Repaint from scratch. Previous content, hit rects and slots are discarded. */
    fun paint(block: SurfacePainter.() -> Unit) {
        canvas.anchorToBounds = true
        canvas.clear(); rects.clear(); slots.clear()
        Painter().block()
    }

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
     */
    internal fun entityOrigin(): Vec3d {
        // No text means no measured block and so no centring to undo.
        if (canvas.itemCount() == 0) return position
        val unit = TextMetrics.PIXEL_SIZE * pixelScale
        val blockHeightPx = canvas.emittedRowCount() * TextMetrics.FONT_LINE_HEIGHT_PX - 1
        // Offset of canvas (0,0) from the entity, in the surface's own frame.
        val localX = unit * (1.0 - canvas.maxRowAdvance() / 2.0)
        val localY = unit * blockHeightPx.toDouble()
        val theta = Math.toRadians(yawDegrees.toDouble())
        val cos = cos(theta)
        val sin = sin(theta)
        // rotateY applied to local +X, matching SurfacePicking's inverse.
        return Vec3d(
            position.x - (localX * cos),
            position.y - localY,
            position.z - (localX * -sin)
        )
    }

    fun toEntity(): VirtualTextDisplay = VirtualTextDisplay().also { d ->
        d.position = entityOrigin()
        d.billboard = orientation
        d.backgroundColor = backdrop ?: DkColor.TRANSPARENT
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
        d.lineWidth = canvas.maxRowAdvance() + LINE_WIDTH_MARGIN_PX

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
                canvas.toTextComponent(anchor = false)
            }
        } else {
            canvas.toTextComponent()
        }
    }

    private inner class Painter : SurfacePainter {

        override fun frame(entry: SpriteEntry, rect: Rect, tint: DkColor?) {
            NineSlicePainter.paint(canvas, entry, rect, tint)
        }

        override fun fill(color: DkColor, rect: Rect) {
            val e = resolveSprite(FILL_SPRITE, "a colour fill") ?: return
            require(e.width > 0 && e.height > 0) {
                "Fill sprite $FILL_SPRITE has non-positive dimensions " +
                    "(${e.width}x${e.height})."
            }
            require(rect.w >= e.width && rect.h >= e.height) {
                "A fill rect must be at least ${e.width}x${e.height} " +
                    "(asked for ${rect.w}x${rect.h}). Tiling needs one whole tile to fit."
            }
            for (y in tileSteps(rect.y, rect.bottom, e.height)) {
                for (x in tileSteps(rect.x, rect.right, e.width)) {
                    canvas.draw(e, x, y, color)
                }
            }
        }

        override fun icon(entry: SpriteEntry, x: Int, y: Int, tint: DkColor?) {
            canvas.draw(entry, x, y, tint)
        }

        override fun label(text: String, x: Int, y: Int, color: DkColor?) {
            canvas.text(text, x, y, color)
        }

        override fun slot(x: Int, y: Int, item: ItemRef?) {
            val e = resolveSprite(SLOT_SPRITE, "a slot") ?: return
            canvas.draw(e, x, y)
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
