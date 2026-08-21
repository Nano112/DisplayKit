package io.schemat.displaykit.surface

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.Billboard
import io.schemat.displaykit.render.Brightness
import io.schemat.displaykit.render.DkColor
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
    }

    /** World blocks per canvas pixel, derived so callers size in blocks. */
    val pixelScale: Float
        get() = targetWidthBlocks / (widthPx * TextMetrics.PIXEL_SIZE)

    private val canvas = SpriteCanvas(widthPx, heightPx)
    private val rects = mutableListOf<HitRect>()
    private val slots = mutableListOf<Pair<Rect, ItemRef>>()

    fun canvasItemCount(): Int = canvas.itemCount()
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

        // Surfaces are glyph-composed, so with no slice source installed the
        // canvas would render as a field of missing-glyph boxes. Say so in
        // words instead — a legible message beats tofu.
        d.text = if (SliceGlyphSource.installed == null && canvas.itemCount() > 0) {
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
            val e = SpriteIndex.bundled.get(FILL_SPRITE) ?: return
            var y = rect.y
            while (y < rect.bottom) {
                var x = rect.x
                while (x < rect.right) {
                    canvas.draw(e, x, y, color)
                    x += e.width
                }
                y += e.height
            }
        }

        override fun icon(entry: SpriteEntry, x: Int, y: Int, tint: DkColor?) {
            canvas.draw(entry, x, y, tint)
        }

        override fun label(text: String, x: Int, y: Int, color: DkColor?) {
            canvas.text(text, x, y, color)
        }

        override fun slot(x: Int, y: Int, item: ItemRef?) {
            SpriteIndex.bundled.get(SLOT_SPRITE)?.let { canvas.draw(it, x, y) }
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
