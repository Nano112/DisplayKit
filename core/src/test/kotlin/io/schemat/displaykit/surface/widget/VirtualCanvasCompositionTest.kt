package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.render.VirtualBlockDisplay
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.surface.PointerButton
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.layout.CanvasInitialPosition
import io.schemat.displaykit.surface.layout.CanvasOverlayAnchor
import io.schemat.displaykit.surface.layout.PxOffset
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.SurfaceNode
import io.schemat.displaykit.surface.layout.VirtualCanvasNode
import io.schemat.displaykit.surface.layout.WidgetNode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VirtualCanvasCompositionTest {
    @BeforeTest fun resetGlyphs() = SpriteGlyphs.clear()
    @AfterTest fun clearGlyphs() = SpriteGlyphs.clear()

    @Test
    fun canvasOwnsCentredTransformClampingAndWholeChildClipping() {
        val surface = Surface(100, 80, Vec3d.ZERO, 2f)
        surface.renderMode = RenderMode.ENTITIES
        val canvas = VirtualCanvasNode("canvas", PxSize(300, 200))
        val child = WidgetNode("child", PxSize(20, 20))
        canvas.addAt(child, 150, 100)
        surface.layout { it.addChild(canvas) }

        assertEquals(canvas.maxPanX() / 2, canvas.panX)
        assertEquals(canvas.maxPanY() / 2, canvas.panY)
        assertEquals(
            io.schemat.displaykit.surface.Rect(
                150 - canvas.panX,
                100 - canvas.panY,
                20,
                20
            ),
            child.rect()
        )
        assertEquals(listOf(child), canvas.visibleChildren())

        assertTrue(canvas.panTo(0, 0))
        assertTrue(canvas.visibleChildren().isEmpty())
        assertTrue(canvas.panTo(999, 999))
        assertEquals(canvas.maxPanX(), canvas.panX)
        assertEquals(canvas.maxPanY(), canvas.panY)
        assertFalse(canvas.panTo(999, 999))
    }

    @Test
    fun centreLeftStartsAtTheBeginningOfAHorizontalCanvasAndCentresVertically() {
        val canvas = VirtualCanvasNode(
            "canvas",
            PxSize(300, 200),
            initialPosition = CanvasInitialPosition.CENTER_LEFT
        )
        val surface = Surface(100, 80, Vec3d.ZERO, 2f)
        surface.renderMode = RenderMode.ENTITIES
        surface.layout { it.addChild(canvas) }

        assertEquals(0, canvas.panX)
        assertEquals(canvas.maxPanY() / 2, canvas.panY)
    }

    @Test
    fun aspectConstrainedViewportFitsAndCentresWithoutChangingParentLayout() {
        val canvas = VirtualCanvasNode(
            "map",
            PxSize(300, 300),
            viewportAspectRatio = 1f
        )
        val surface = Surface(100, 80, Vec3d.ZERO, 2f)
        surface.renderMode = RenderMode.ENTITIES
        surface.layout { it.addChild(canvas) }

        assertEquals(io.schemat.displaykit.surface.Rect(5, 0, 89, 89), canvas.viewportRect())
        assertEquals(100, canvas.rect().w, "the canvas still participates in the full parent layout")
        assertEquals(null, canvas.hitTest(4, 40), "letterbox space is not interactive map content")
        assertEquals(canvas, canvas.hitTest(6, 40))
    }

    @Test
    fun overlayChildrenStayViewportAnchoredAndWinHitTestingAfterContentPans() {
        val canvas = VirtualCanvasNode("canvas", PxSize(300, 200))
        val content = WidgetNode("content", PxSize(40, 40))
        val control = WidgetNode("control", PxSize(22, 22))
        canvas.addAt(content, 150, 100)
        canvas.addOverlay(
            control,
            CanvasOverlayAnchor.CENTER_RIGHT,
            PxOffset(-4, 0)
        )
        val surface = Surface(100, 80, Vec3d.ZERO, 2f)
        surface.renderMode = RenderMode.ENTITIES
        surface.layout { it.addChild(canvas) }

        val viewport = canvas.viewportRect()
        val anchored = io.schemat.displaykit.surface.Rect(
            viewport.right - 22 - 4,
            viewport.y + (viewport.h - 22) / 2,
            22,
            22
        )
        assertEquals(anchored, control.rect())
        canvas.panTo(0, 0)
        assertEquals(anchored, control.rect())
        assertEquals(control, canvas.hitTest(anchored.x + 1, anchored.y + 1))
    }

    @Test
    fun canvasPanControlsMoveThroughTheCanvasClamp() {
        val canvas = VirtualCanvasNode(
            "canvas",
            PxSize(300, 200),
            initialPosition = CanvasInitialPosition.CENTER_LEFT
        )
        val controls = canvas.addPanControls(
            "pan",
            isHovered = { false },
            style = CanvasPanControlsStyle(step = 30)
        )
        val surface = Surface(100, 80, Vec3d.ZERO, 2f)
        surface.renderMode = RenderMode.ENTITIES
        surface.layout { it.addChild(canvas) }

        val right = assertNotNull(controls[CanvasPanDirection.RIGHT])
        val rect = right.rect()
        surface.dispatch(
            SurfaceEvent.Click(rect.centeredX(1), rect.centeredY(1), PointerButton.LEFT),
            target = right
        )
        assertEquals(30, canvas.panX)
    }

    @Test
    fun grabLifecycleMakesEachDragRelativeWithoutASecondGrabJump() {
        var changed = 0
        val canvas = VirtualCanvasNode(
            "canvas", PxSize(300, 200), onViewportChanged = { changed++ }
        )
        val surface = Surface(100, 80, Vec3d.ZERO, 2f)
        surface.renderMode = RenderMode.ENTITIES
        surface.layout { it.addChild(canvas) }
        val startX = canvas.panX
        val startY = canvas.panY

        canvas.onGrabStart?.invoke()
        canvas.onGrabMove?.invoke(60, 50)
        canvas.onGrabMove?.invoke(45, 30)
        canvas.onGrabEnd?.invoke()
        assertEquals(startX + 15, canvas.panX)
        assertEquals(startY + 20, canvas.panY)
        assertEquals(1, changed)

        canvas.onGrabStart?.invoke()
        canvas.onGrabMove?.invoke(5, 5)
        assertEquals(startX + 15, canvas.panX)
        assertEquals(startY + 20, canvas.panY)
    }

    @Test
    fun skillTreeEvaluatesPrerequisitesAndOwnsUnlockDispatch() {
        val unlocked = linkedSetOf("root")
        val skills = listOf(
            SkillDefinition("root", "Root", 60, 50, SpriteId("items", "item/book")),
            SkillDefinition("branch", "Branch", 140, 30, SpriteId("items", "item/wheat"), listOf("root")),
            SkillDefinition("leaf", "Leaf", 140, 75, SpriteId("items", "item/iron_ingot"), listOf("branch"))
        )
        val tree = SkillTreeView(
            "skills", PxSize(200, 110), skills,
            isUnlocked = unlocked::contains,
            isHovered = { false },
            onUnlock = { unlocked += it.id },
            onViewportChanged = {}
        )
        val surface = Surface(200, 110, Vec3d.ZERO, 2f)
        surface.renderMode = RenderMode.ENTITIES
        surface.layout { it.addChild(tree.node) }

        fun click(id: String) {
            val node = assertNotNull(surface.root?.find("skills-skill-$id"))
            val r = node.rect()
            surface.dispatch(
                SurfaceEvent.Click(r.x + r.w / 2, r.y + r.h / 2, PointerButton.LEFT),
                target = node
            )
        }

        click("leaf")
        assertFalse("leaf" in unlocked)
        click("branch")
        assertTrue("branch" in unlocked)
        click("leaf")
        assertTrue("leaf" in unlocked)
    }

    @Test
    fun skillTreeComposesOnePhysicalBackgroundAndNonOverlappingBlockRails() {
        val tree = SkillTreeView(
            "skills",
            PxSize(300, 120),
            listOf(
                SkillDefinition("root", "Root", 60, 60, SpriteId("items", "item/book")),
                SkillDefinition(
                    "branch",
                    "Branch",
                    200,
                    80,
                    SpriteId("items", "item/wheat"),
                    listOf("root")
                )
            ),
            isUnlocked = { it == "root" },
            isHovered = { false },
            onUnlock = {},
            onViewportChanged = {}
        )
        val surface = Surface(300, 120, Vec3d.ZERO, 2f)
        surface.renderMode = RenderMode.COMPOSITED
        surface.layout { it.addChild(tree.node) }

        val root = assertNotNull(surface.root?.find("skills-skill-root"))
        val branch = assertNotNull(surface.root?.find("skills-skill-branch"))
        assertEquals(32 + 6 + TextMetrics.textWidthPx("Root") + 2, root.rect().w)
        assertEquals(32 + 6 + TextMetrics.textWidthPx("Branch") + 2, branch.rect().w)
        assertEquals(32, root.rect().h)
        assertEquals(32, branch.rect().h)

        surface.paintTree()

        val blocks = surface.toEntities().filterIsInstance<VirtualBlockDisplay>()
        assertEquals(1, blocks.count { it.blockState == BlockStateRef.GRAY_CONCRETE })
        assertEquals(3, blocks.count { it.blockState == BlockStateRef.YELLOW_CONCRETE })
    }

    @Test
    fun arbitraryPanPhasesArePreparedBeforeTheFirstPaint() {
        val tree = SkillTreeView(
            "skills",
            PxSize(240, 140),
            listOf(
                SkillDefinition("root", "Root", 120, 70, SpriteId("items", "item/wheat"))
            ),
            isUnlocked = { true },
            isHovered = { false },
            onUnlock = {},
            onViewportChanged = {}
        )
        val surface = Surface(120, 80, Vec3d.ZERO, 2f)
        surface.renderMode = RenderMode.COMPOSITED
        surface.layout { it.addChild(tree.node) }
        surface.paintTree()
        val prepared = SpriteGlyphs.requested().size

        for (panY in 25..34) {
            tree.canvas.panTo(tree.canvas.panX, panY)
            surface.paintTree()
        }
        assertEquals(prepared, SpriteGlyphs.requested().size)
    }

    @Test
    fun tileMapKeepsDenseDataCompositedAndEachCellAddressable() {
        val cells = (0 until 25).flatMap { y ->
            (0 until 25).map { x -> TileMapCell("$x-$y", x, y, x + y) }
        }
        var selected: TileMapCell<Int>? = null
        val map = TileMapView(
            id = "grid",
            columns = 25,
            rows = 25,
            cells = cells,
            color = { if (it.value % 2 == 0) io.schemat.displaykit.render.DkColor.WHITE else io.schemat.displaykit.render.DkColor.GRAY },
            selectedId = { selected?.id },
            isHovered = { false },
            onSelected = { selected = it },
            onViewportChanged = {}
        )
        val surface = Surface(220, 180, Vec3d.ZERO, 3f)
        surface.renderMode = RenderMode.COMPOSITED
        surface.layout { it.addChild(map.node) }
        surface.paintTree()

        val cell = assertNotNull(surface.root?.find("grid-cell-12-12"))
        val rect = cell.rect()
        surface.dispatch(
            SurfaceEvent.Click(rect.centeredX(1), rect.centeredY(1), PointerButton.LEFT),
            target = cell
        )
        assertEquals("12-12", selected?.id)
        assertTrue(surface.toEntities().size < 10, "625 tiles must remain shared composited layers")
        assertTrue(surface.toEntities().none { it is VirtualBlockDisplay })
    }

    @Test
    fun tileMapHoverReusesPreparedInsetGeometry() {
        var hovered = false
        val map = TileMapView(
            id = "grid",
            columns = 1,
            rows = 1,
            cells = listOf(TileMapCell("cell", 0, 0, Unit)),
            color = { io.schemat.displaykit.render.DkColor.WHITE },
            selectedId = { null },
            isHovered = { hovered && it == "grid-cell-cell" },
            onSelected = {},
            onViewportChanged = {},
            style = TileMapStyle(cellSize = 11),
        )
        val surface = Surface(100, 80, Vec3d.ZERO, 2f)
        surface.renderMode = RenderMode.COMPOSITED
        surface.layout { it.addChild(map.node) }
        surface.paintTree()
        val prepared = SpriteGlyphs.requested().size

        hovered = true
        surface.paintTree()

        assertEquals(
            prepared,
            SpriteGlyphs.requested().size,
            "the first hover must reuse the inset tile variants prepared before pack build",
        )
    }

    @Test
    fun tileMapRejectsAmbiguousOrOutOfBoundsData() {
        assertFailsWith<IllegalArgumentException> {
            TileMapView(
                "grid", 1, 1,
                listOf(TileMapCell("same", 0, 0, 1), TileMapCell("same", 0, 0, 2)),
                color = { io.schemat.displaykit.render.DkColor.WHITE },
                selectedId = { null }, isHovered = { false }, onSelected = {},
                onViewportChanged = {}
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TileMapView(
                "grid", 1, 1, listOf(TileMapCell("outside", 1, 0, 1)),
                color = { io.schemat.displaykit.render.DkColor.WHITE },
                selectedId = { null }, isHovered = { false }, onSelected = {},
                onViewportChanged = {}
            )
        }
    }

    private fun SurfaceNode.find(wanted: String): SurfaceNode? {
        if (id == wanted) return this
        return children.firstNotNullOfOrNull { it.find(wanted) }
    }
}
