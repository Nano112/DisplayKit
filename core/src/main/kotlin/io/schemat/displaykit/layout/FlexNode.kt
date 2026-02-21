package io.schemat.displaykit.layout

class FlexNode(
    id: String,
    var direction: FlexDirection = FlexDirection.Row
) : BaseLayoutNode(id) {

    var mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start
    var crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start
    var gap: Float = 0f

    private val isRow: Boolean
        get() = direction == FlexDirection.Row || direction == FlexDirection.RowReverse

    private val isReversed: Boolean
        get() = direction == FlexDirection.RowReverse || direction == FlexDirection.ColumnReverse

    override fun measure(constraints: Constraints): Size {
        val c = applyOwnConstraints(constraints)

        if (_children.isEmpty()) {
            val size = finalizeSize(Size(padding.horizontal, padding.vertical), c)
            layoutResult = LayoutResult(Offset.Zero, size)
            return size
        }

        val availableMain = if (isRow) {
            (c.maxWidth - padding.horizontal).coerceAtLeast(0f)
        } else {
            (c.maxHeight - padding.vertical).coerceAtLeast(0f)
        }

        val availableCross = if (isRow) {
            (c.maxHeight - padding.vertical).coerceAtLeast(0f)
        } else {
            (c.maxWidth - padding.horizontal).coerceAtLeast(0f)
        }

        val hasBoundedMain = availableMain.isFinite() && availableMain < 10000f
        val hasBoundedCross = availableCross.isFinite() && availableCross < 10000f

        // Pass 1: Main axis sizing
        val totalGaps = if (_children.size > 1) gap * (_children.size - 1) else 0f
        val childMainSizes = mutableListOf<Float>()
        var totalFixedMain = 0f
        var totalFlexGrow = 0f

        for (child in _children) {
            val hasFlexGrow = child.flexGrow > 0
            if (hasFlexGrow && hasBoundedMain && child.flexBasis == null) {
                childMainSizes.add(0f)
                totalFlexGrow += child.flexGrow
            } else {
                val mainConstraint = child.flexBasis ?: Float.MAX_VALUE
                val crossConstraint = if (hasBoundedCross) availableCross else Float.MAX_VALUE
                val measureConstraints = if (isRow) {
                    Constraints(maxWidth = mainConstraint, maxHeight = crossConstraint)
                } else {
                    Constraints(maxWidth = crossConstraint, maxHeight = mainConstraint)
                }
                val childSize = child.measure(measureConstraints)
                val mainSize = if (isRow) childSize.width else childSize.height
                childMainSizes.add(mainSize)
                totalFixedMain += mainSize
            }
        }

        val remainingMain = if (hasBoundedMain) {
            (availableMain - totalGaps - totalFixedMain).coerceAtLeast(0f)
        } else 0f

        if (totalFlexGrow > 0 && remainingMain > 0) {
            _children.forEachIndexed { index, child ->
                if (child.flexGrow > 0 && childMainSizes[index] == 0f) {
                    childMainSizes[index] = (remainingMain * child.flexGrow) / totalFlexGrow
                }
            }
        }

        // Shrinking
        val totalMainBeforeShrink = childMainSizes.sum()
        if (hasBoundedMain && totalMainBeforeShrink > (availableMain - totalGaps)) {
            val overflow = totalMainBeforeShrink - (availableMain - totalGaps)
            val totalShrink = _children.mapIndexed { index, child ->
                child.flexShrink * childMainSizes[index]
            }.sum()
            if (totalShrink > 0) {
                _children.forEachIndexed { index, child ->
                    if (child.flexShrink > 0) {
                        val shrinkFactor = child.flexShrink * childMainSizes[index]
                        childMainSizes[index] = (childMainSizes[index] - (overflow * shrinkFactor) / totalShrink).coerceAtLeast(0f)
                    }
                }
            }
        }

        // Pass 2: Cross axis sizing
        var maxChildCross = 0f
        val childCrossSizes = mutableListOf<Float>()

        for ((index, child) in _children.withIndex()) {
            val childMainSize = childMainSizes[index]
            val crossConstraint = when {
                crossAxisAlignment == CrossAxisAlignment.Stretch && hasBoundedCross -> availableCross
                else -> Float.MAX_VALUE
            }
            val childConstraints = if (isRow) {
                Constraints(minWidth = childMainSize, maxWidth = childMainSize, maxHeight = crossConstraint)
            } else {
                Constraints(maxWidth = crossConstraint, minHeight = childMainSize, maxHeight = childMainSize)
            }
            val childSize = child.measure(childConstraints)
            val childCrossSize = if (isRow) childSize.height else childSize.width
            childCrossSizes.add(childCrossSize)
            maxChildCross = maxOf(maxChildCross, childCrossSize)
        }

        // Final container size
        val totalMainAfterFlex = childMainSizes.sum() + totalGaps
        val finalMainSize = if (isRow) {
            if (hasBoundedMain) c.constrainWidth(availableMain + padding.horizontal)
            else c.constrainWidth(totalMainAfterFlex + padding.horizontal)
        } else {
            if (hasBoundedMain) c.constrainHeight(availableMain + padding.vertical)
            else c.constrainHeight(totalMainAfterFlex + padding.vertical)
        }
        val finalCrossSize = if (isRow) {
            if (hasBoundedCross) c.constrainHeight(availableCross + padding.vertical)
            else c.constrainHeight(maxChildCross + padding.vertical)
        } else {
            if (hasBoundedCross) c.constrainWidth(availableCross + padding.horizontal)
            else c.constrainWidth(maxChildCross + padding.horizontal)
        }

        val size = if (isRow) Size(finalMainSize, finalCrossSize) else Size(finalCrossSize, finalMainSize)
        layoutResult = LayoutResult(Offset.Zero, size)

        // Pass 3: Position children
        positionChildren(childMainSizes, childCrossSizes, size)
        return size
    }

    private fun positionChildren(childMainSizes: List<Float>, childCrossSizes: List<Float>, containerSize: Size) {
        val contentMain = childMainSizes.sum() + (if (_children.size > 1) gap * (_children.size - 1) else 0f)
        val availableMain = if (isRow) containerSize.width - padding.horizontal else containerSize.height - padding.vertical
        val availableCross = if (isRow) containerSize.height - padding.vertical else containerSize.width - padding.horizontal

        var mainPos = when (mainAxisAlignment) {
            MainAxisAlignment.Start -> 0f
            MainAxisAlignment.End -> availableMain - contentMain
            MainAxisAlignment.Center -> (availableMain - contentMain) / 2
            MainAxisAlignment.SpaceBetween -> 0f
            MainAxisAlignment.SpaceAround -> (availableMain - childMainSizes.sum()) / (_children.size * 2)
            MainAxisAlignment.SpaceEvenly -> (availableMain - childMainSizes.sum()) / (_children.size + 1)
        }

        val effectiveGap = when (mainAxisAlignment) {
            MainAxisAlignment.SpaceBetween -> if (_children.size > 1) (availableMain - childMainSizes.sum()) / (_children.size - 1) else 0f
            MainAxisAlignment.SpaceAround -> (availableMain - childMainSizes.sum()) / (_children.size * 2) * 2
            MainAxisAlignment.SpaceEvenly -> (availableMain - childMainSizes.sum()) / (_children.size + 1)
            else -> gap
        }

        val indices = if (isReversed) _children.indices.reversed() else _children.indices.toList()

        for (i in indices) {
            val child = _children[i]
            val childMainSize = childMainSizes[i]
            val childCrossSize = childCrossSizes[i]

            val crossPos = when (crossAxisAlignment) {
                CrossAxisAlignment.Start -> 0f
                CrossAxisAlignment.End -> availableCross - childCrossSize
                CrossAxisAlignment.Center -> (availableCross - childCrossSize) / 2
                CrossAxisAlignment.Stretch -> 0f
            }

            val offset = if (isRow) {
                Offset(padding.left + mainPos, padding.top + crossPos)
            } else {
                Offset(padding.left + crossPos, padding.top + mainPos)
            }
            child.place(offset)
            mainPos += childMainSize + effectiveGap
        }
    }
}

fun RowNode(
    id: String,
    mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start,
    crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
    gap: Float = 0f
): FlexNode = FlexNode(id, FlexDirection.Row).apply {
    this.mainAxisAlignment = mainAxisAlignment
    this.crossAxisAlignment = crossAxisAlignment
    this.gap = gap
}

fun ColumnNode(
    id: String,
    mainAxisAlignment: MainAxisAlignment = MainAxisAlignment.Start,
    crossAxisAlignment: CrossAxisAlignment = CrossAxisAlignment.Start,
    gap: Float = 0f
): FlexNode = FlexNode(id, FlexDirection.Column).apply {
    this.mainAxisAlignment = mainAxisAlignment
    this.crossAxisAlignment = crossAxisAlignment
    this.gap = gap
}
