package io.schemat.displaykit.sprite

/**
 * Where a fitted sprite actually lands inside its box.
 *
 * The one place this is computed. `iconFitted` scales a sprite to fit and
 * then CENTRES it, so the drawn y depends on the sprite's own fitted height
 * -- and a glyph's ascent is baked per y, so anything that wants to pre-warm
 * that glyph has to arrive at the identical y.
 *
 * Pre-warming used the cell's top-left instead, and got away with it for
 * square icons, which fill their box and centre to an offset of zero. Wide
 * sprites do not: the whole `gui` atlas is panels, every one fits to a
 * different height, every one centres to a different y, and every one was
 * therefore a variant the warm-up missed -- so scrolling that tab allocated
 * new codepoints and re-downloaded the pack, exactly what warming was
 * supposed to prevent.
 *
 * Sharing the arithmetic is the point: a painter and its pre-warm that
 * compute placement separately WILL drift, and the failure is silent until
 * someone notices the pack reloading.
 */
object SpriteFit {

    /** Rendered height of [entry] fitted into a [boxW] x [boxH] box. */
    fun height(entry: SpriteEntry, boxW: Int, boxH: Int): Int = entry.fitHeight(boxW, boxH)

    /**
     * Top-left the fitted sprite is drawn at, given the box's own top-left
     * ([x], [y]). Centred both ways.
     */
    fun origin(entry: SpriteEntry, x: Int, y: Int, boxW: Int, boxH: Int): Pair<Int, Int> {
        val h = height(entry, boxW, boxH)
        val w = entry.scaledWidth(h)
        return (x + (boxW - w) / 2) to (y + (boxH - h) / 2)
    }
}
