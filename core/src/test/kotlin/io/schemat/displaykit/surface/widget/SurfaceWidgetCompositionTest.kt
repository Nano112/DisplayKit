package io.schemat.displaykit.surface.widget

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.NineSliceLayout
import io.schemat.displaykit.surface.PointerButton
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceEvent
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.SurfaceNode
import io.schemat.displaykit.surface.layout.WidgetNode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurfaceWidgetCompositionTest {

    private fun entry(index: Int) = SpriteEntry(
        SpriteId("items", "widget-test-$index"),
        16,
        16,
        "minecraft:item/widget-test-$index.png"
    )

    private fun SurfaceNode.find(id: String): SurfaceNode? {
        if (this.id == id) return this
        return children.firstNotNullOfOrNull { it.find(id) }
    }

    @BeforeTest fun resetGlyphs() = SpriteGlyphs.clear()
    @AfterTest fun clearGlyphs() = SpriteGlyphs.clear()

    @Test
    fun windowOwnsExactFrameTitleAndBodyGeometry() {
        val window = SurfaceWindow.vanilla(400, 220)
        val frame = SpriteIndex.bundled.get(window.style.frameId)
        assertTrue(window.size.w >= 400 && window.size.h >= 220)
        if (frame != null) {
            assertTrue(NineSliceLayout.tilesEvenly(frame, window.size.w, window.size.h))
        }

        val surface = Surface(window.size.w, window.size.h, Vec3d.ZERO, 3f)
        surface.renderMode = RenderMode.ENTITIES
        window.configure(surface)
        surface.layout { root ->
            window.build(root, "Window", {}) { body ->
                body.addChild(WidgetNode("content", PxSize(20, 20)))
            }
        }

        val root = assertNotNull(surface.root)
        assertEquals(
            io.schemat.displaykit.surface.Rect(0, 0, window.size.w, window.size.h),
            assertNotNull(root.find("window-frame")).rect()
        )
        val title = assertNotNull(root.find("window-title")).rect()
        assertEquals(window.style.padding, title.x)
        assertEquals(window.style.padding, title.y)
        assertEquals(window.size.w - window.style.padding * 2, title.w)
        assertEquals(TextMetrics.centringHeight(window.style.titleMinHeight), title.h)
        val body = assertNotNull(root.find("window-body")).rect()
        assertEquals(title.bottom + window.style.columnGap, body.y)
        assertEquals(title.w, body.w)
    }

    @Test
    fun tabStripOwnsPitchHitTargetsAndEveryStatePreparation() {
        val values = listOf("items", "blocks", "gui")
        val strip = BlockTabStrip(
            id = "tab",
            values = values,
            selected = { "items" },
            label = { it },
            isHovered = { false },
            onSelected = {}
        )
        val surface = Surface(240, 120, Vec3d.ZERO, 3f)
        surface.renderMode = RenderMode.ENTITIES
        surface.layout { it.addChild(strip.node) }
        surface.paintTree()

        val tabs = surface.hitRects().filter { it.id.startsWith("tab-") }
        assertEquals(values.map { "tab-$it" }, tabs.map { it.id })
        val pitch = TextMetrics.centringHeight(20)
        assertEquals(listOf(pitch, pitch), tabs.zipWithNext { a, b -> b.rect.y - a.rect.y })
    }

    @Test
    fun tabbedViewSharesSelectionBetweenTabsPaintingAndPageHitTesting() {
        var selected = "map"
        val map = WidgetNode("map-page", PxSize(80, 40))
        val skills = WidgetNode("skills-page", PxSize(120, 60))
        val tabs = TabbedView(
            id = "workspace",
            pages = listOf(
                TabbedPage("map", "Map", map),
                TabbedPage("skills", "Skills", skills)
            ),
            selected = { selected },
            isHovered = { false },
            onSelected = { selected = it }
        )
        val surface = Surface(260, 120, Vec3d.ZERO, 3f)
        surface.renderMode = RenderMode.ENTITIES
        surface.layout { it.addChild(tabs.node) }

        assertEquals(listOf(map), tabs.pageStack.visibleChildren())
        val skillsTab = assertNotNull(surface.root?.find("workspace-tabs-Skills"))
        val r = skillsTab.rect()
        surface.dispatch(
            SurfaceEvent.Click(r.centeredX(1), r.centeredY(1), PointerButton.LEFT),
            target = skillsTab
        )

        assertEquals("skills", selected)
        assertEquals(listOf(skills), tabs.pageStack.visibleChildren())
        assertEquals(skills, tabs.pageStack.hitTest(skills.rect().x + 1, skills.rect().y + 1))
    }

    @Test
    fun actionButtonReadsLiveStateAndRejectsDisabledClicks() {
        var enabled = false
        var label = "Waiting"
        var clicked = false
        val button = ActionButtonView(
            id = "apply",
            label = { label },
            enabled = { enabled },
            isHovered = { false },
            onClick = { clicked = true }
        )
        val surface = Surface(220, 60, Vec3d.ZERO, 3f)
        surface.renderMode = RenderMode.ENTITIES
        surface.layout { it.addChild(button.node) }
        val rect = button.node.rect()

        val disabled = surface.dispatch(
            SurfaceEvent.Click(rect.centeredX(1), rect.centeredY(1), PointerButton.LEFT),
            target = button.node
        )
        assertEquals(null, disabled)
        assertFalse(clicked)

        enabled = true
        label = "Apply"
        val active = surface.dispatch(
            SurfaceEvent.Click(rect.centeredX(1), rect.centeredY(1), PointerButton.LEFT),
            target = button.node
        )
        assertEquals(button.node, active)
        assertTrue(clicked)
    }

    @Test
    fun spriteGridDerivesColumnsFromGrantedWidth() {
        val items = (0 until 25).map(::entry)

        fun rowsAt(width: Int): Int {
            val surface = Surface(width, 100, Vec3d.ZERO, 3f)
            surface.renderMode = RenderMode.ENTITIES
            val grid = SpriteGridNode(
                "grid", items,
                isHovered = { false },
                onClick = {}
            )
            surface.layout { it.addChild(grid) }
            return grid.children.size
        }

        assertEquals(3, rowsAt(250)) // 12 columns
        assertEquals(7, rowsAt(90))  // 4 columns
    }

    @Test
    fun gridAndScrollbarPrepareAllFutureScrollStatesFromPlacedRects() {
        val items = (0 until 40).map(::entry)
        val surface = Surface(120, 100, Vec3d.ZERO, 3f)
        surface.renderMode = RenderMode.COMPOSITED
        lateinit var grid: SpriteGridNode
        surface.layout { root ->
            grid = SpriteGridNode(
                "grid", items,
                preloadItems = items,
                possibleItemCounts = listOf(20, 40),
                isHovered = { false },
                onClick = {}
            )
            val scroll = VerticalScrollView(
                "scroll",
                grid,
                grid::possibleMaxScrolls,
                onScrollChanged = {}
            )
            root.addChild(scroll.node)
        }

        surface.paintTree()
        val baseline = SpriteGlyphs.requested().size
        assertTrue(baseline >= items.size, "every future grid sprite must be prepared")
        while (grid.scrollBy(1)) surface.paintTree()
        assertEquals(baseline, SpriteGlyphs.requested().size)

        val scroll = assertNotNull(surface.root?.find("scroll-scrollbar"))
        scroll.onGrabMove?.invoke(scroll.rect().x, scroll.rect().bottom)
        assertEquals(grid.maxScroll(), grid.scrollPx)
    }
}
