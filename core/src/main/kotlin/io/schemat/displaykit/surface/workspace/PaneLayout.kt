package io.schemat.displaykit.surface.workspace

import io.schemat.displaykit.surface.layout.CrossAxis
import io.schemat.displaykit.surface.layout.FlexDirection
import io.schemat.displaykit.surface.layout.FlexNode
import io.schemat.displaykit.surface.layout.SurfaceNode

/**
 * Turns a [PaneTree] into a [SurfaceNode] tree the surface layout engine can
 * measure and place.
 *
 * The whole reason splitting is cheap: [FlexNode] already distributes integer
 * weights and hands indivisible remainders to the earliest child, so children
 * always sum to exactly the parent's inner size. A [PaneTree.Split]'s ratio is
 * therefore expressible with no arithmetic of our own and no rounding boundary
 * -- the ratio becomes two `flexGrow` weights and the engine does the rest.
 * Every rounding boundary introduced into this subsystem so far has produced a
 * visible defect, so not introducing one is the point.
 */
object PaneLayout {

    /**
     * Build the node tree for [tree], calling [buildPane] to fill each leaf.
     *
     * [buildPane] receives the pane's id and returns the node that renders it
     * -- a terminal, a log tail, whatever the pane holds. This class knows
     * nothing about pane contents, which is what keeps it testable with stub
     * leaves.
     *
     * Panes STRETCH across the cross axis: a pane occupies its whole share of
     * the split in both directions, which is what makes a split look like a
     * split rather than like two floating boxes.
     */
    fun build(
        tree: PaneTree,
        idPrefix: String = "pane-split",
        buildPane: (String) -> SurfaceNode
    ): SurfaceNode {
        // Wrapped unconditionally, including for a single leaf. Only a split's
        // children get a grow weight, so an unwrapped lone pane keeps its
        // intrinsic size and a one-pane screen renders a tiny box in the
        // corner of an empty surface. Making the root fill only in the split
        // case is exactly the "works once you add a second one" shape of bug
        // this subsystem keeps producing, so the root fills in every case.
        val root = FlexNode(
            id = "$idPrefix-root",
            direction = FlexDirection.COLUMN,
            crossAxis = CrossAxis.STRETCH
        )
        val content = build(tree, idPrefix, buildPane, path = "")
        content.flexGrow = 1
        root.addChild(content)
        return root
    }

    private fun build(
        tree: PaneTree,
        idPrefix: String,
        buildPane: (String) -> SurfaceNode,
        path: String
    ): SurfaceNode = when (tree) {
        is PaneTree.Leaf -> buildPane(tree.id)

        is PaneTree.Split -> {
            val node = FlexNode(
                id = "$idPrefix$path",
                direction = when (tree.axis) {
                    SplitAxis.SIDE_BY_SIDE -> FlexDirection.ROW
                    SplitAxis.STACKED -> FlexDirection.COLUMN
                },
                // Without STRETCH each child sizes to its own content and the
                // split stops filling its region.
                crossAxis = CrossAxis.STRETCH
            )
            val first = build(tree.first, idPrefix, buildPane, "$path-0")
            val second = build(tree.second, idPrefix, buildPane, "$path-1")
            // The ratio IS the weight pair. Both must be non-zero or a child
            // with weight 0 collapses to its intrinsic size instead of taking
            // its share -- PaneTree's MIN_RATIO already guarantees that, and
            // this is where it earns its keep.
            first.flexGrow = tree.ratio
            second.flexGrow = PaneTree.WHOLE - tree.ratio
            node.addChild(first)
            node.addChild(second)
            node
        }
    }
}
