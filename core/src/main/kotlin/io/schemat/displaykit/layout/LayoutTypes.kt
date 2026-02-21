package io.schemat.displaykit.layout

data class Constraints(
    val minWidth: Float = 0f,
    val maxWidth: Float = Float.MAX_VALUE,
    val minHeight: Float = 0f,
    val maxHeight: Float = Float.MAX_VALUE
) {
    val hasFixedWidth: Boolean get() = minWidth == maxWidth
    val hasFixedHeight: Boolean get() = minHeight == maxHeight
    val hasBoundedWidth: Boolean get() = maxWidth != Float.MAX_VALUE
    val hasBoundedHeight: Boolean get() = maxHeight != Float.MAX_VALUE

    fun constrainWidth(width: Float): Float = width.coerceIn(minWidth, maxWidth)
    fun constrainHeight(height: Float): Float = height.coerceIn(minHeight, maxHeight)
    fun constrain(size: Size): Size = Size(constrainWidth(size.width), constrainHeight(size.height))
    fun fixedWidth(width: Float): Constraints = copy(minWidth = width, maxWidth = width)
    fun fixedHeight(height: Float): Constraints = copy(minHeight = height, maxHeight = height)
    fun unbounded(): Constraints = copy(maxWidth = Float.MAX_VALUE, maxHeight = Float.MAX_VALUE)

    companion object {
        fun fixed(width: Float, height: Float) = Constraints(minWidth = width, maxWidth = width, minHeight = height, maxHeight = height)
        fun maxSize(width: Float, height: Float) = Constraints(maxWidth = width, maxHeight = height)
        val Unbounded = Constraints()
    }
}

data class Size(val width: Float, val height: Float) {
    companion object {
        val Zero = Size(0f, 0f)
    }
}

data class Offset(val x: Float, val y: Float) {
    operator fun plus(other: Offset) = Offset(x + other.x, y + other.y)
    operator fun minus(other: Offset) = Offset(x - other.x, y - other.y)

    companion object {
        val Zero = Offset(0f, 0f)
    }
}

data class Padding(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f
) {
    val horizontal: Float get() = left + right
    val vertical: Float get() = top + bottom

    companion object {
        val Zero = Padding()
        fun all(value: Float) = Padding(value, value, value, value)
        fun symmetric(horizontal: Float = 0f, vertical: Float = 0f) = Padding(horizontal, vertical, horizontal, vertical)
    }
}

enum class MainAxisAlignment {
    Start, End, Center, SpaceBetween, SpaceAround, SpaceEvenly
}

enum class CrossAxisAlignment {
    Start, End, Center, Stretch
}

enum class FlexDirection {
    Row, RowReverse, Column, ColumnReverse
}

data class LayoutResult(
    val offset: Offset,
    val size: Size
) {
    val x: Float get() = offset.x
    val y: Float get() = offset.y
    val width: Float get() = size.width
    val height: Float get() = size.height
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}
