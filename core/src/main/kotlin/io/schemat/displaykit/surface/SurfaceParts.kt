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
fun SurfacePainter.button(id: String, rect: Rect, text: String, onClick: () -> Unit) {
    val sprite = resolveSprite(BUTTON, "button '$id'") ?: return
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
fun SurfacePainter.titleBar(rect: Rect, title: String, onClose: () -> Unit) {
    fill(DkColor(255, 32, 34, 40), rect)
    label(title, rect.x + 4, rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2)
    val cross = resolveSprite(CROSS, "a title bar's close button") ?: return
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
fun SurfacePainter.scrollTrack(rect: Rect) {
    frame(resolveSprite(SCROLL_TRACK, "a scrollbar track") ?: return, rect)
}

/**
 * The draggable thumb.
 *
 * Minimum size is 6x32 — the `gui/widget/scroller` sprite's own. [frame] will
 * throw if [rect] is smaller.
 */
fun SurfacePainter.scrollThumb(rect: Rect) {
    frame(resolveSprite(SCROLL_THUMB, "a scrollbar thumb") ?: return, rect)
}

/**
 * A tab, either selected or unselected.
 *
 * Minimum size is 130x24 — both `gui/widget/tab` and `gui/widget/tab_selected`
 * share that size. [frame] will throw if [rect] is smaller.
 *
 * Skipped entirely when its sprite does not resolve — see [button].
 */
fun SurfacePainter.tab(id: String, rect: Rect, text: String, selected: Boolean, onClick: () -> Unit) {
    val spriteId = if (selected) TAB_SELECTED else TAB
    val sprite = resolveSprite(spriteId, "tab '$id'") ?: return
    frame(sprite, rect)
    val textWidth = TextMetrics.textWidthPx(text)
    label(text, rect.x + (rect.w - textWidth) / 2, rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2)
    region(id, rect, onClick)
}
