package io.schemat.displaykit.page

data class PageBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val centerX: Float get() = x + width / 2
    val centerY: Float get() = y - height / 2
    val right: Float get() = x + width
    val bottom: Float get() = y - height

    companion object {
        fun centered(width: Float, height: Float): PageBounds {
            return PageBounds(x = -width / 2, y = height / 2, width = width, height = height)
        }
    }

    fun splitHorizontal(ratio: Float, gap: Float = 0f): Pair<PageBounds, PageBounds> {
        val leftWidth = (width - gap) * ratio
        val rightWidth = (width - gap) * (1 - ratio)
        val left = PageBounds(x, y, leftWidth, height)
        val right = PageBounds(x + leftWidth + gap, y, rightWidth, height)
        return Pair(left, right)
    }

    fun splitVertical(ratio: Float, gap: Float = 0f): Pair<PageBounds, PageBounds> {
        val topHeight = (height - gap) * ratio
        val bottomHeight = (height - gap) * (1 - ratio)
        val top = PageBounds(x, y, width, topHeight)
        val bottom = PageBounds(x, y - topHeight - gap, width, bottomHeight)
        return Pair(top, bottom)
    }

    fun inset(padding: Float): PageBounds {
        return PageBounds(
            x = x + padding, y = y - padding,
            width = (width - padding * 2).coerceAtLeast(0f),
            height = (height - padding * 2).coerceAtLeast(0f)
        )
    }

    fun inset(left: Float, top: Float, right: Float, bottom: Float): PageBounds {
        return PageBounds(
            x = x + left, y = y - top,
            width = (width - left - right).coerceAtLeast(0f),
            height = (height - top - bottom).coerceAtLeast(0f)
        )
    }
}
