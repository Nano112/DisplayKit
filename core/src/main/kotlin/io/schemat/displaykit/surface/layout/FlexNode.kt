package io.schemat.displaykit.surface.layout

enum class FlexDirection { ROW, COLUMN }
enum class MainAxis { START, CENTER, END, SPACE_BETWEEN }

/**
 * Cross-axis placement for a [FlexNode]'s children.
 *
 * [STRETCH] applies to every child, growing or not: the child is measured
 * with a tight cross-axis constraint equal to the parent's inner cross
 * extent (width for a COLUMN, height for a ROW), instead of a loosened
 * shrink-to-fit one. A child with its own explicit `width`/`height` still
 * wins over this, because [BaseSurfaceNode.measure] applies that override
 * on top of whatever constraint the parent hands down.
 */
enum class CrossAxis { START, CENTER, END, STRETCH }

/**
 * Lays children out along one axis, distributing leftover space by integer
 * weight.
 *
 * Mirrors the vocabulary of the world-space [io.schemat.displaykit.layout]
 * engine on purpose, in integers. See the spec: the duplication is deliberate,
 * because a float layout upstream of integer glyph placement reintroduces the
 * rounding drift this subsystem keeps producing.
 */
class FlexNode(
    id: String,
    var direction: FlexDirection = FlexDirection.ROW,
    var mainAxis: MainAxis = MainAxis.START,
    var crossAxis: CrossAxis = CrossAxis.START,
    var gap: Int = 0
) : BaseSurfaceNode(id) {

    private val isRow get() = direction == FlexDirection.ROW

    private fun main(s: PxSize) = if (isRow) s.w else s.h
    private fun cross(s: PxSize) = if (isRow) s.h else s.w

    override fun measureSelf(c: PxConstraints): PxSize {
        val inner = c.deflate(padding)
        val totalGap = if (_children.isEmpty()) 0 else gap * (_children.size - 1)
        val mainAvail = (if (isRow) inner.maxW else inner.maxH) - totalGap

        // Pass 1: measure non-growing children at their natural size, except
        // on the cross axis where CrossAxis.STRETCH asks for the parent's
        // inner cross extent instead of a loosened (shrink-to-fit) one. This
        // mirrors pass 2 below: an explicit width/height on the child still
        // wins, because BaseSurfaceNode.measure overrides whatever
        // constraint we hand it here with the child's own fixed size.
        //
        // The stretch floor is pinned to inner.maxW/maxH, not inner.minW/
        // minH: this FlexNode's OWN incoming constraint is routinely loose
        // (BoxNode.measureSelf hands every child `inner.loosen()`, so a
        // FlexNode sitting directly under a Surface's root has minW == 0
        // even though maxW is the real available width). maxW/maxH is the
        // one value that is always the true inner extent regardless of how
        // loose the incoming constraint was, so it is what "tight equal to
        // the inner cross extent" has to mean.
        val sizes = arrayOfNulls<PxSize>(_children.size)
        var usedMain = 0
        val stretchNonGrowing = crossAxis == CrossAxis.STRETCH
        for ((i, child) in _children.withIndex()) {
            if (child.flexGrow > 0) continue
            val cc = if (isRow) {
                PxConstraints(
                    0, inner.maxW,
                    if (stretchNonGrowing) inner.maxH else 0, inner.maxH
                )
            } else {
                PxConstraints(
                    if (stretchNonGrowing) inner.maxW else 0, inner.maxW,
                    0, inner.maxH
                )
            }
            val s = child.measure(cc)
            sizes[i] = s
            usedMain += main(s)
        }

        // Pass 2: split what is left by weight, remainder to the earliest.
        val weights = _children.sumOf { it.flexGrow }
        if (weights > 0) {
            val leftover = (mainAvail - usedMain).coerceAtLeast(0)
            val share = leftover / weights
            var remainder = leftover - share * weights
            for ((i, child) in _children.withIndex()) {
                if (child.flexGrow == 0) continue
                var give = share * child.flexGrow
                // One pixel at a time to the earliest children, so the sum is
                // exactly `leftover` and a row never comes up short.
                val take = minOf(remainder, child.flexGrow)
                give += take
                remainder -= take
                // Loosen the cross axis unless the caller asked to stretch --
                // otherwise a growing child is force-filled on the cross axis
                // and CENTER/END can never move it. Pinned to inner.maxH/
                // maxW rather than inner.minH/minW for the same reason as
                // pass 1 above: the incoming constraint's floor is not a
                // reliable stand-in for the inner extent when this FlexNode
                // itself was measured loosely.
                val stretch = crossAxis == CrossAxis.STRETCH
                val cc = if (isRow) {
                    PxConstraints(
                        give, give,
                        if (stretch) inner.maxH else 0,
                        inner.maxH
                    )
                } else {
                    PxConstraints(
                        if (stretch) inner.maxW else 0,
                        inner.maxW,
                        give, give
                    )
                }
                sizes[i] = child.measure(cc)
            }
        }

        val contentMain = sizes.filterNotNull().sumOf { main(it) } + totalGap
        val contentCross = sizes.filterNotNull().maxOfOrNull { cross(it) } ?: 0
        val size = if (isRow) {
            PxSize(contentMain + padding.horizontal, contentCross + padding.vertical)
        } else {
            PxSize(contentCross + padding.horizontal, contentMain + padding.vertical)
        }
        return c.constrain(size)
    }

    override fun place(offset: PxOffset) {
        super.place(offset)
        val size = layoutResult?.size ?: return
        val innerMain = (if (isRow) size.w else size.h) -
            (if (isRow) padding.horizontal else padding.vertical)
        val innerCross = (if (isRow) size.h else size.w) -
            (if (isRow) padding.vertical else padding.horizontal)

        val childSizes = _children.map { it.layoutResult?.size ?: PxSize.Zero }
        val totalGap = if (_children.isEmpty()) 0 else gap * (_children.size - 1)
        val contentMain = childSizes.sumOf { main(it) } + totalGap
        val slack = (innerMain - contentMain).coerceAtLeast(0)

        var cursor = when (mainAxis) {
            MainAxis.START, MainAxis.SPACE_BETWEEN -> 0
            MainAxis.CENTER -> slack / 2
            MainAxis.END -> slack
        }
        val gaps = if (_children.size > 1) _children.size - 1 else 0
        val betweenBase = if (mainAxis == MainAxis.SPACE_BETWEEN && gaps > 0) slack / gaps else 0
        var betweenRemainder =
            if (mainAxis == MainAxis.SPACE_BETWEEN && gaps > 0) slack - betweenBase * gaps else 0

        for ((i, child) in _children.withIndex()) {
            val cs = childSizes[i]
            val crossPos = when (crossAxis) {
                CrossAxis.START, CrossAxis.STRETCH -> 0
                CrossAxis.CENTER -> (innerCross - cross(cs)) / 2
                CrossAxis.END -> innerCross - cross(cs)
            }
            val childOffset = if (isRow) {
                PxOffset(padding.left + cursor, padding.top + crossPos)
            } else {
                PxOffset(padding.left + crossPos, padding.top + cursor)
            }
            child.place(childOffset)
            cursor += main(cs) + gap
            if (i < _children.size - 1) {
                // One pixel at a time to the earliest gaps, so the sum of
                // gaps is exactly `slack` and the last child reaches the far
                // edge instead of stopping short from truncation.
                var extra = betweenBase
                if (betweenRemainder > 0) { extra += 1; betweenRemainder -= 1 }
                cursor += extra
            }
        }
    }
}
