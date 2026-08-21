package io.schemat.displaykit.surface.layout

enum class FlexDirection { ROW, COLUMN }
enum class MainAxis { START, CENTER, END, SPACE_BETWEEN }
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

        // Pass 1: measure non-growing children at their natural size.
        val sizes = arrayOfNulls<PxSize>(_children.size)
        var usedMain = 0
        for ((i, child) in _children.withIndex()) {
            if (child.flexGrow > 0) continue
            val s = child.measure(inner.loosen())
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
                val cc = if (isRow) {
                    PxConstraints(give, give, inner.minH, inner.maxH)
                } else {
                    PxConstraints(inner.minW, inner.maxW, give, give)
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
        val between = if (mainAxis == MainAxis.SPACE_BETWEEN && _children.size > 1) {
            slack / (_children.size - 1)
        } else 0

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
            cursor += main(cs) + gap + between
        }
    }
}
