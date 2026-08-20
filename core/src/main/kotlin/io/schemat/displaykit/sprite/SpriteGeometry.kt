package io.schemat.displaykit.sprite

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3f
import io.schemat.displaykit.render.TextMetrics
import org.joml.Matrix4f

/**
 * Geometry for rendering an atlas sprite on a text_display at exact
 * block-pixel scale.
 *
 * A sprite in a text component renders as a square glyph 8 text-pixels tall,
 * regardless of the source texture's real shape. At transformation scale 1
 * that glyph is `8 * TextMetrics.PIXEL_SIZE = 0.2` world units. For one source
 * pixel to equal one block pixel (1/16 = 0.0625 world units):
 *
 *     scale = W * 0.0625 / 0.2 = W * 0.3125
 *
 * The centering translations are PIXEL_SIZE multiples of the *resulting
 * scale*, not of the pixel dimensions — X compensates a one-pixel advance
 * overhang, Y lifts the bottom-anchored glyph onto its visual center. Sprite
 * glyphs have different vertical extents from text, which is why Y does not
 * reuse [TextMetrics.verticalCenterCorrection].
 */
object SpriteGeometry {

    /** Transformation scale that renders one sprite pixel as one block pixel. */
    const val BLOCK_PIXEL_SCALE = 0.3125f

    /** Horizontal centering factor: one text pixel of advance overhang. */
    const val X_CENTERING = -TextMetrics.PIXEL_SIZE          // -0.025

    /** Vertical centering factor: six text pixels. */
    const val Y_CENTERING = -6f * TextMetrics.PIXEL_SIZE     // -0.15

    /**
     * Transformation scale for [entry].
     *
     * @param scale multiplier over block-pixel scale; 1.0 means one sprite
     *   pixel renders exactly one block pixel.
     */
    fun scaleFor(entry: SpriteEntry, scale: Float = 1f): Vec3f = Vec3f(
        x = entry.width * BLOCK_PIXEL_SCALE * scale,
        y = entry.height * BLOCK_PIXEL_SCALE * scale,
        z = 1f
    )

    /** Translation that centers a glyph already scaled to [scale]. */
    fun centeringTranslation(scale: Vec3f): Vec3f = Vec3f(
        x = scale.x * X_CENTERING,
        y = scale.y * Y_CENTERING,
        z = 0f
    )

    /** Rendered size in world units (blocks). */
    fun worldSize(entry: SpriteEntry, scale: Float = 1f): Vec3f = Vec3f(
        x = entry.width * scale / 16f,
        y = entry.height * scale / 16f,
        z = 0f
    )

    /** Complete transformation: centered, at block-pixel scale. */
    fun transform(entry: SpriteEntry, scale: Float = 1f): Mat4f {
        val s = scaleFor(entry, scale)
        val t = centeringTranslation(s)
        return Mat4f(
            Matrix4f()
                .translation(t.x, t.y, t.z)
                .scale(s.x, s.y, s.z)
        )
    }
}
