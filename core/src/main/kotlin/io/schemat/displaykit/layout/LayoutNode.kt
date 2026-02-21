package io.schemat.displaykit.layout

interface LayoutNode {
    val id: String
    var parent: LayoutNode?
    val children: List<LayoutNode>
    var layoutResult: LayoutResult?
    var padding: Padding
    var width: Float?
    var height: Float?
    var minWidth: Float
    var maxWidth: Float
    var minHeight: Float
    var maxHeight: Float
    var flexGrow: Float
    var flexShrink: Float
    var flexBasis: Float?

    fun measure(constraints: Constraints): Size
    fun place(offset: Offset)
    fun addChild(child: LayoutNode)
    fun removeChild(child: LayoutNode)
    fun clearChildren()

    fun getAbsoluteOffset(): Offset {
        val parentOffset = parent?.getAbsoluteOffset() ?: Offset.Zero
        val myOffset = layoutResult?.offset ?: Offset.Zero
        return parentOffset + myOffset
    }
}

abstract class BaseLayoutNode(override val id: String) : LayoutNode {
    override var parent: LayoutNode? = null
    protected val _children = mutableListOf<LayoutNode>()
    override val children: List<LayoutNode> get() = _children
    override var layoutResult: LayoutResult? = null
    override var padding: Padding = Padding.Zero
    override var width: Float? = null
    override var height: Float? = null
    override var minWidth: Float = 0f
    override var maxWidth: Float = Float.MAX_VALUE
    override var minHeight: Float = 0f
    override var maxHeight: Float = Float.MAX_VALUE
    override var flexGrow: Float = 0f
    override var flexShrink: Float = 1f
    override var flexBasis: Float? = null

    override fun addChild(child: LayoutNode) {
        child.parent = this
        _children.add(child)
    }

    override fun removeChild(child: LayoutNode) {
        child.parent = null
        _children.remove(child)
    }

    override fun clearChildren() {
        _children.forEach { it.parent = null }
        _children.clear()
    }

    override fun place(offset: Offset) {
        layoutResult = layoutResult?.copy(offset = offset) ?: LayoutResult(offset, Size.Zero)
    }

    protected fun applyOwnConstraints(constraints: Constraints): Constraints {
        var c = constraints
        width?.let { w -> c = c.copy(minWidth = w, maxWidth = w) }
        height?.let { h -> c = c.copy(minHeight = h, maxHeight = h) }
        c = c.copy(
            minWidth = maxOf(c.minWidth, minWidth),
            maxWidth = minOf(c.maxWidth, maxWidth),
            minHeight = maxOf(c.minHeight, minHeight),
            maxHeight = minOf(c.maxHeight, maxHeight)
        )
        return c
    }

    protected fun finalizeSize(size: Size, constraints: Constraints): Size {
        return Size(constraints.constrainWidth(size.width), constraints.constrainHeight(size.height))
    }
}

class LeafNode(
    id: String,
    var intrinsicWidth: Float = 0f,
    var intrinsicHeight: Float = 0f
) : BaseLayoutNode(id) {

    override fun measure(constraints: Constraints): Size {
        val c = applyOwnConstraints(constraints)
        val effectiveWidth = when {
            intrinsicWidth > 0 -> intrinsicWidth
            c.hasFixedWidth -> c.maxWidth
            c.hasBoundedWidth && c.maxWidth < 10000f -> c.maxWidth
            else -> 0f
        }
        val effectiveHeight = when {
            intrinsicHeight > 0 -> intrinsicHeight
            c.hasFixedHeight -> c.maxHeight
            c.hasBoundedHeight && c.maxHeight < 10000f -> c.maxHeight
            else -> 0f
        }
        val size = finalizeSize(Size(effectiveWidth, effectiveHeight), c)
        layoutResult = LayoutResult(Offset.Zero, size)
        return size
    }

    override fun addChild(child: LayoutNode) {
        throw UnsupportedOperationException("LeafNode cannot have children")
    }
}

class BoxNode(id: String) : BaseLayoutNode(id) {
    override fun measure(constraints: Constraints): Size {
        val c = applyOwnConstraints(constraints)
        val childConstraints = Constraints(
            minWidth = (c.minWidth - padding.horizontal).coerceAtLeast(0f),
            maxWidth = (c.maxWidth - padding.horizontal).coerceAtLeast(0f),
            minHeight = (c.minHeight - padding.vertical).coerceAtLeast(0f),
            maxHeight = (c.maxHeight - padding.vertical).coerceAtLeast(0f)
        )
        val child = _children.firstOrNull()
        val childSize = child?.measure(childConstraints) ?: Size.Zero
        val size = finalizeSize(
            Size(childSize.width + padding.horizontal, childSize.height + padding.vertical), c
        )
        layoutResult = LayoutResult(Offset.Zero, size)
        child?.place(Offset(padding.left, padding.top))
        return size
    }
}
