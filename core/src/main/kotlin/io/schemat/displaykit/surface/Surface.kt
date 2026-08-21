package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.Billboard
import io.schemat.displaykit.render.Brightness
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextAlignment
import io.schemat.displaykit.render.TextComponent
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.render.VirtualTextDisplay
import io.schemat.displaykit.sprite.SpriteCanvas
import io.schemat.displaykit.sprite.SpriteDiagnostics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import org.joml.Matrix4f

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
        canvas.clear(); rects.clear(); slots.clear()
        Painter().block()
    }

    fun toEntity(): VirtualTextDisplay = VirtualTextDisplay().also { d ->
        d.position = position
        d.billboard = orientation
        d.backgroundColor = DkColor.TRANSPARENT
        d.brightness = Brightness.FULL
        d.hasShadow = false
        val s = pixelScale
        d.transformation = Mat4f(Matrix4f().scale(s, s, s))

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
        d.text = if (SliceGlyphSource.installed == null && canvas.requiresPack()) {
            SpriteDiagnostics.packDisabled()
            TextComponent.of("[DisplayKit surface unavailable: resource pack disabled]")
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
