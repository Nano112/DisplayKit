package io.schemat.displaykit.surface.workspace

/**
 * How a [PaneTree.Split] arranges its two children.
 *
 * Named after the resulting arrangement rather than after the divider, because
 * every terminal multiplexer disagrees about which one "horizontal split"
 * means and the ambiguity is a reliable source of inverted layouts.
 */
enum class SplitAxis {
    /** Children sit left and right of each other, divider running vertically. */
    SIDE_BY_SIDE,

    /** Children sit above and below each other, divider running horizontally. */
    STACKED
}

/**
 * The arrangement of panes within one screen: a binary tree whose leaves are
 * panes and whose branches are splits.
 *
 * Immutable. Every operation returns a new tree, which keeps split and close
 * total and side-effect free -- they are user actions a few times a session,
 * so nothing here is on a hot path, and a value type is far easier to assert
 * against than an in-place mutation of a shared structure.
 *
 * This models arrangement ONLY. What a pane contains, where its screen sits in
 * the world, and which pane has focus all live elsewhere: a pane is just an id
 * here. Keeping it that way is what makes the whole thing testable without a
 * client, a server, or a render pass.
 */
sealed interface PaneTree {

    /** One pane. [id] is opaque here and must be unique within a tree. */
    data class Leaf(val id: String) : PaneTree

    /**
     * Two children sharing this region, [first] taking [ratio] percent of the
     * split axis.
     *
     * [ratio] is an integer percent rather than a float, for the same reason
     * the surface layout engine is integer throughout: every rounding boundary
     * introduced into this subsystem has produced a visible defect, and a
     * percentage that cannot be represented exactly is one more of them. The
     * renderer turns it straight into `flexGrow` weights, and `FlexNode`
     * already hands indivisible remainders to the earliest child, so children
     * always sum to exactly the parent's inner size.
     */
    data class Split(
        val axis: SplitAxis,
        val first: PaneTree,
        val second: PaneTree,
        val ratio: Int = 50
    ) : PaneTree {
        init {
            require(ratio in MIN_RATIO..MAX_RATIO) {
                "ratio must be in $MIN_RATIO..$MAX_RATIO percent, got $ratio"
            }
        }
    }

    companion object {
        /**
         * A pane may not be squeezed below this share of its split.
         *
         * Not cosmetic: `SurfaceParts` throws outright when a frame is asked
         * to draw smaller than its sprite's native size, so a pane dragged to
         * nothing takes the whole surface down with it rather than merely
         * looking bad.
         */
        const val MIN_RATIO = 10
        const val MAX_RATIO = 90

        /** Percent of a split, in total. */
        const val WHOLE = 100

        init {
            // resize() takes the complement of a ratio when you grab the
            // second pane, and a complement only lands back inside the legal
            // range if the bounds are symmetric about half. Asserted here so
            // widening one bound alone fails at class load rather than
            // clamping a drag to a value Split's constructor then rejects.
            require(MIN_RATIO + MAX_RATIO == WHOLE) {
                "MIN_RATIO and MAX_RATIO must be symmetric about $WHOLE/2"
            }
        }
    }
}

/** Every pane id, left-to-right then top-to-bottom. */
fun PaneTree.panes(): List<String> = when (this) {
    is PaneTree.Leaf -> listOf(id)
    is PaneTree.Split -> first.panes() + second.panes()
}

/** True if [paneId] is somewhere in this tree. */
fun PaneTree.contains(paneId: String): Boolean = panes().contains(paneId)

/**
 * Replace the leaf [paneId] with a split of itself and a new pane [newPaneId].
 *
 * The existing pane becomes [SplitAxis]'s first child, so splitting always
 * puts the new pane right of, or below, the one you were looking at -- the
 * convention every multiplexer shares and the one that makes repeated splits
 * predictable.
 *
 * Returns the tree unchanged if [paneId] is absent. Throws if [newPaneId] is
 * already present, since a duplicate id would make [panes] ambiguous and
 * silently break focus routing rather than failing where the mistake was made.
 */
fun PaneTree.split(
    paneId: String,
    axis: SplitAxis,
    newPaneId: String,
    ratio: Int = 50
): PaneTree {
    require(!contains(newPaneId)) { "pane id '$newPaneId' is already in this tree" }
    return splitInternal(paneId, axis, newPaneId, ratio)
}

private fun PaneTree.splitInternal(
    paneId: String,
    axis: SplitAxis,
    newPaneId: String,
    ratio: Int
): PaneTree = when (this) {
    is PaneTree.Leaf ->
        if (id == paneId) PaneTree.Split(axis, this, PaneTree.Leaf(newPaneId), ratio)
        else this

    is PaneTree.Split -> copy(
        first = first.splitInternal(paneId, axis, newPaneId, ratio),
        second = second.splitInternal(paneId, axis, newPaneId, ratio)
    )
}

/**
 * Remove [paneId], collapsing its parent split so the sibling takes the whole
 * region.
 *
 * Returns null when the tree held only [paneId] -- an empty screen has no
 * valid [PaneTree] representation, and null says "this screen is now empty"
 * rather than inventing a placeholder pane the caller did not ask for. The
 * caller decides whether that closes the screen.
 *
 * Returns the tree unchanged if [paneId] is absent.
 */
fun PaneTree.close(paneId: String): PaneTree? = when (this) {
    is PaneTree.Leaf -> if (id == paneId) null else this

    is PaneTree.Split -> {
        val f = first.close(paneId)
        val s = second.close(paneId)
        when {
            // The removed pane was directly below us: promote the survivor,
            // discarding this split's ratio along with the split itself.
            f == null -> s
            s == null -> f
            else -> copy(first = f, second = s)
        }
    }
}

/**
 * Set the ratio of the split immediately containing [paneId], clamped into
 * [PaneTree.MIN_RATIO]..[PaneTree.MAX_RATIO].
 *
 * Clamped rather than rejected: this is what a resize drag calls on every
 * pointer move, and a drag that runs past the edge should stop at the edge,
 * not throw. [PaneTree.Split]'s own constructor still rejects an out-of-range
 * ratio, so a bad value constructed any other way fails loudly.
 *
 * [ratio] is always expressed relative to the pane's own side of the split, so
 * dragging feels the same whichever pane you grabbed.
 */
fun PaneTree.resize(paneId: String, ratio: Int): PaneTree {
    val clamped = ratio.coerceIn(PaneTree.MIN_RATIO, PaneTree.MAX_RATIO)
    return when (this) {
        is PaneTree.Leaf -> this
        is PaneTree.Split -> when {
            first is PaneTree.Leaf && first.id == paneId -> copy(ratio = clamped)
            // Grabbing the second pane asks for ITS share, so the split --
            // which is always stored as the first child's share -- takes the
            // complement. Legal by the symmetry invariant asserted above.
            second is PaneTree.Leaf && second.id == paneId ->
                copy(ratio = PaneTree.WHOLE - clamped)
            else -> copy(first = first.resize(paneId, ratio), second = second.resize(paneId, ratio))
        }
    }
}

/**
 * The pane after [paneId] in [panes] order, wrapping at the end.
 *
 * The "focus next pane" binding. Returns null only for an unknown [paneId];
 * a single-pane tree cycles to itself, which is the correct no-op.
 */
fun PaneTree.nextPane(paneId: String): String? {
    val all = panes()
    val i = all.indexOf(paneId)
    if (i < 0) return null
    return all[(i + 1) % all.size]
}
