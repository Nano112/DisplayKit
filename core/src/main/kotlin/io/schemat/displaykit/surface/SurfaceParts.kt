package io.schemat.displaykit.surface

import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteId

/**
 * Worked examples of composition, not a widget API.
 *
 * Each of these is an ordinary function over the parts in [SurfacePainter].
 * Read them to see how to assemble your own; call the parts directly when you
 * want something these do not cover.
 */

private val BUTTON = SpriteId("gui", "widget/button")
private val CROSS = SpriteId("gui", "widget/cross_button")
private val TAB = SpriteId("gui", "widget/tab")
private val TAB_SELECTED = SpriteId("gui", "widget/tab_selected")
private val SCROLL_TRACK = SpriteId("gui", "widget/scroller_background")
private val SCROLL_THUMB = SpriteId("gui", "widget/scroller")

/**
 * A framed, labelled, clickable button.
 *
 * Minimum size is 200x20 — the `gui/widget/button` sprite's own. [frame] will
 * throw if [rect] is smaller.
 *
 * If the frame sprite does not resolve the whole button is skipped, label and
 * hit region included: an unlabelled, unframed, still-clickable rectangle is
 * worse than nothing there at all.
 */
fun SurfacePainter.button(id: String, rect: Rect, text: String, onClick: () -> Unit) = elevate {
    val sprite = resolveSprite(BUTTON, "button '$id'") ?: return@elevate
    frame(sprite, rect)
    val textWidth = TextMetrics.textWidthPx(text)
    label(text, rect.x + (rect.w - textWidth) / 2, rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2)
    region(id, rect, onClick)
}

/**
 * A filled strip with a title and a close button at its right edge.
 *
 * Minimum height is 16 — [fill]'s fill sprite is 16x16, and [rect] must be at
 * least that tall (and wide) or [fill] will throw.
 */
fun SurfacePainter.titleBar(rect: Rect, title: String, onClose: () -> Unit) = elevate {
    fill(DkColor(255, 32, 34, 40), rect)
    label(title, rect.x + 4, rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2)
    val cross = resolveSprite(CROSS, "a title bar's close button") ?: return@elevate
    val cx = rect.right - cross.width - 2
    val cy = rect.y + (rect.h - cross.height) / 2
    icon(cross, cx, cy)
    region("close", Rect(cx, cy, cross.width, cross.height), onClose)
}

/**
 * The groove a scrollbar thumb runs in.
 *
 * Minimum size is 6x32 — the `gui/widget/scroller_background` sprite's own.
 * [frame] will throw if [rect] is smaller.
 */
fun SurfacePainter.scrollTrack(rect: Rect) = elevate {
    frame(resolveSprite(SCROLL_TRACK, "a scrollbar track") ?: return@elevate, rect)
}

/**
 * Snap [y] down to the nearest multiple of [TextMetrics.FONT_LINE_HEIGHT_PX].
 *
 * Pulled out of [scrollThumb] as its own pure function so the snap itself can
 * be asserted directly, without going through a painter.
 */
internal fun snapToLinePitch(y: Int): Int =
    (y / TextMetrics.FONT_LINE_HEIGHT_PX) * TextMetrics.FONT_LINE_HEIGHT_PX

/**
 * The draggable thumb.
 *
 * Minimum size is 6x32 — the `gui/widget/scroller` sprite's own. [frame] will
 * throw if [rect] is smaller.
 *
 * The thumb's Y is snapped to the renderer's line pitch. A glyph's ascent is
 * baked per vertical phase, so an unsnapped thumb lands on a new phase every
 * time it moves, allocates new codepoints, and forces a full client resource
 * reload mid-scroll. Snapping costs at most a few pixels of thumb precision
 * on a track hundreds of pixels tall, and it belongs here rather than in each
 * window: the picker had it, the terminal did not, and the terminal shipped a
 * download loop.
 */
fun SurfacePainter.scrollThumb(rect: Rect) = elevate(2) {
    val snapped = rect.copy(y = snapToLinePitch(rect.y))
    frame(resolveSprite(SCROLL_THUMB, "a scrollbar thumb") ?: return@elevate, snapped)
}

/**
 * A tab, either selected or unselected.
 *
 * Minimum size is 130x24 — both `gui/widget/tab` and `gui/widget/tab_selected`
 * share that size. [frame] will throw if [rect] is smaller.
 *
 * Skipped entirely when its sprite does not resolve — see [button].
 */
fun SurfacePainter.tab(id: String, rect: Rect, text: String, selected: Boolean, onClick: () -> Unit) = elevate {
    val spriteId = if (selected) TAB_SELECTED else TAB
    val sprite = resolveSprite(spriteId, "tab '$id'") ?: return@elevate
    frame(sprite, rect)
    val textWidth = TextMetrics.textWidthPx(text)
    label(text, rect.x + (rect.w - textWidth) / 2, rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2)
    region(id, rect, onClick)
}
