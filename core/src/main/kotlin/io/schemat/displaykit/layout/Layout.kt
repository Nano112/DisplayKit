package io.schemat.displaykit.layout

class Layout(
    val width: Float,
    val height: Float
) {
    private var root: LayoutNode? = null
    private val nodeMap = mutableMapOf<String, LayoutNode>()

    fun setRoot(node: LayoutNode) {
        root = node
        registerNode(node)
    }

    private fun registerNode(node: LayoutNode) {
        nodeMap[node.id] = node
        node.children.forEach { registerNode(it) }
    }

    fun compute() {
        val rootNode = root ?: return
        val constraints = Constraints.fixed(width, height)
        rootNode.measure(constraints)
        rootNode.place(Offset.Zero)
    }

    fun getResult(id: String): LayoutResult? = nodeMap[id]?.layoutResult
    fun getAbsolutePosition(id: String): Offset? = nodeMap[id]?.getAbsoluteOffset()
    fun getNode(id: String): LayoutNode? = nodeMap[id]

    inner class LayoutBuilder(private val parent: LayoutNode?) {
        fun leaf(
            id: String, intrinsicWidth: Float = 0f, intrinsicHeight: Float = 0f,
            width: Float? = null, height: Float? = null,
            flexGrow: Float = 0f, flexShrink: Float = 1f, flexBasis: Float? = null,
            padding: Padding = Padding.Zero
        ): LeafNode {
            val node = LeafNode(id, intrinsicWidth, intrinsicHeight).apply {
                this.width = width; this.height = height
                this.flexGrow = flexGrow; this.flexShrink = flexShrink; this.flexBasis = flexBasis
                this.padding = padding
            }
            addToParentOrRoot(node)
            return node
        }

        fun spacer(id: String = "spacer_${System.nanoTime()}", flexGrow: Float = 1f, width: Float? = null, height: Float? = null): LeafNode {
            return leaf(id, width = width, height = height, flexGrow = flexGrow)
        }

        fun box(
            id: String, width: Float? = null, height: Float? = null,
            padding: Padding = Padding.Zero, flexGrow: Float = 0f, flexShrink: Float = 1f,
            block: LayoutBuilder.() -> Unit = {}
        ): BoxNode {
            val node = BoxNode(id).apply {
                this.width = width; this.height = height; this.padding = padding
                this.flexGrow = flexGrow; this.flexShrink = flexShrink
            }
            addToParentOrRoot(node)
            LayoutBuilder(node).block()
            return node
        }

        fun row(
            id: String, width: Float? = null, height: Float? = null,
            padding: Padding = Padding.Zero, gap: Float = 0f,
            mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start,
            crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
            flexGrow: Float = 0f, flexShrink: Float = 1f,
            block: LayoutBuilder.() -> Unit = {}
        ): FlexNode {
            val node = RowNode(id, mainAxisAlignment, crossAxisAlignment, gap).apply {
                this.width = width; this.height = height; this.padding = padding
                this.flexGrow = flexGrow; this.flexShrink = flexShrink
            }
            addToParentOrRoot(node)
            LayoutBuilder(node).block()
            return node
        }

        fun column(
            id: String, width: Float? = null, height: Float? = null,
            padding: Padding = Padding.Zero, gap: Float = 0f,
            mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start,
            crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
            flexGrow: Float = 0f, flexShrink: Float = 1f,
            block: LayoutBuilder.() -> Unit = {}
        ): FlexNode {
            val node = ColumnNode(id, mainAxisAlignment, crossAxisAlignment, gap).apply {
                this.width = width; this.height = height; this.padding = padding
                this.flexGrow = flexGrow; this.flexShrink = flexShrink
            }
            addToParentOrRoot(node)
            LayoutBuilder(node).block()
            return node
        }

        private fun addToParentOrRoot(node: LayoutNode) {
            nodeMap[node.id] = node
            if (parent != null) parent.addChild(node) else root = node
        }
    }

    fun build(block: LayoutBuilder.() -> Unit) { LayoutBuilder(null).block() }

    fun row(
        id: String, padding: Padding = Padding.Zero, gap: Float = 0f,
        mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start,
        crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
        block: LayoutBuilder.() -> Unit = {}
    ): FlexNode {
        val node = RowNode(id, mainAxisAlignment, crossAxisAlignment, gap).apply {
            this.padding = padding; this.width = this@Layout.width; this.height = this@Layout.height
        }
        root = node; nodeMap[node.id] = node
        LayoutBuilder(node).block()
        return node
    }

    fun column(
        id: String, padding: Padding = Padding.Zero, gap: Float = 0f,
        mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start,
        crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
        block: LayoutBuilder.() -> Unit = {}
    ): FlexNode {
        val node = ColumnNode(id, mainAxisAlignment, crossAxisAlignment, gap).apply {
            this.padding = padding; this.width = this@Layout.width; this.height = this@Layout.height
        }
        root = node; nodeMap[node.id] = node
        LayoutBuilder(node).block()
        return node
    }

    fun clear() { root = null; nodeMap.clear() }

    fun debugPrint(): String {
        val sb = StringBuilder()
        fun printNode(node: LayoutNode, indent: Int) {
            val prefix = "  ".repeat(indent)
            val result = node.layoutResult
            sb.appendLine("$prefix${node.id}: ${result?.offset} ${result?.size}")
            node.children.forEach { printNode(it, indent + 1) }
        }
        root?.let { printNode(it, 0) }
        return sb.toString()
    }
}
