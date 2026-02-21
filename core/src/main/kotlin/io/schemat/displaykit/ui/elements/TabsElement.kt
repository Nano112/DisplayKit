package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement
import org.joml.Matrix4f

data class TabDefinition(
    val id: String,
    val label: String,
    val icon: String? = null
)

class TabsElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    val tabs: List<TabDefinition>,
    val tabWidth: Float = 0.8f,
    val tabHeight: Float = 0.3f,
    val gap: Float = 0.05f,
    var selectedIndex: Int = 0,
    val onTabChange: (Int, TabDefinition) -> Unit = { _, _ -> }
) : UIElement(
    ui = ui, localOffset = localOffset,
    isInteractive = false, hitboxSize = 0.0
) {
    private val tabBackgrounds = mutableListOf<VirtualBlockDisplay>()
    private val tabLabels = mutableListOf<VirtualTextDisplay>()
    private val tabButtons = mutableListOf<TabButtonElement>()
    private var hoveredTabIndex: Int = -1

    val totalWidth: Float
        get() = tabs.size * tabWidth + (tabs.size - 1) * gap

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)
        val startX = -totalWidth / 2 + tabWidth / 2

        tabs.forEachIndexed { index, tab ->
            val tabX = startX + index * (tabWidth + gap)

            // Tab background
            val bg = VirtualBlockDisplay().apply {
                position = pos
                brightness = Brightness.FULL
                blockState = getTabMaterial(index)
                transformation = ui.buildUIElementMatrix(
                    localOffsetX = tabX - tabWidth / 2, localOffsetY = -tabHeight / 2, localOffsetZ = 0f,
                    scaleX = tabWidth, scaleY = tabHeight, scaleZ = 0.02f
                )
            }
            tabBackgrounds.add(bg)
            spawnEntity(bg)

            // Tab label
            val labelText = if (tab.icon != null) "${tab.icon}${tab.label}" else tab.label
            val labelPos = ui.calculatePosition(localOffset.x + tabX, localOffset.y, localOffset.z - 0.15)
            val label = VirtualTextDisplay().apply {
                position = labelPos
                text = TextComponent.of(labelText)
                billboard = Billboard.CENTER
                backgroundColor = DkColor.TRANSPARENT
                isSeeThrough = false
                brightness = Brightness.FULL
                textAlignment = TextAlignment.CENTER
                viewRange = 1.0f
                transformation = Mat4f(Matrix4f().scaling(0.35f, 0.35f, 0.35f))
            }
            tabLabels.add(label)
            spawnEntity(label)

            // Tab button (invisible hit target)
            val tabButton = TabButtonElement(
                ui = ui,
                localOffset = Vec3d(localOffset.x + tabX, localOffset.y, localOffset.z),
                tabIndex = index, tabWidth = tabWidth, tabHeight = tabHeight,
                parent = this
            )
            tabButton.spawn()
            tabButtons.add(tabButton)
        }

        updateAllTabs()
    }

    private fun getTabMaterial(index: Int): BlockStateRef = when {
        index == selectedIndex -> BlockStateRef.LIGHT_BLUE_CONCRETE
        index == hoveredTabIndex -> BlockStateRef.CYAN_CONCRETE
        else -> BlockStateRef.GRAY_CONCRETE
    }

    private fun updateAllTabs() {
        tabBackgrounds.forEachIndexed { index, display ->
            display.blockState = getTabMaterial(index)
            updateEntity(display)
        }
        tabLabels.forEachIndexed { index, display ->
            val tab = tabs[index]
            val labelText = if (tab.icon != null) "${tab.icon}${tab.label}" else tab.label
            display.text = TextComponent.of(labelText)
            updateEntity(display)
        }
    }

    internal fun onTabClicked(index: Int) {
        if (index != selectedIndex && index in tabs.indices) {
            selectedIndex = index
            updateAllTabs()
            onTabChange(index, tabs[index])
        }
    }

    internal fun onTabHoverChanged(index: Int, isHovered: Boolean) {
        hoveredTabIndex = if (isHovered) index else -1
        if (index in tabBackgrounds.indices) {
            tabBackgrounds[index].blockState = getTabMaterial(index)
            updateEntity(tabBackgrounds[index])
        }
    }

    fun selectTab(index: Int) {
        if (index in tabs.indices) {
            selectedIndex = index
            updateAllTabs()
            onTabChange(index, tabs[index])
        }
    }

    fun getTabButtons(): List<TabButtonElement> = tabButtons.toList()

    override fun destroy() {
        destroyAllEntities()
        tabButtons.forEach { it.destroy() }
        tabBackgrounds.clear()
        tabLabels.clear()
        tabButtons.clear()
    }

    override fun onHoverChanged() {}
}

class TabButtonElement(
    ui: FloatingUI,
    localOffset: Vec3d,
    val tabIndex: Int,
    tabWidth: Float,
    tabHeight: Float,
    private val parent: TabsElement
) : UIElement(
    ui = ui, localOffset = localOffset,
    isInteractive = true, hitboxSize = 0.0,
    hitboxWidth = tabWidth.toDouble(), hitboxHeight = tabHeight.toDouble()
) {
    init {
        this.onClick = { parent.onTabClicked(tabIndex) }
    }

    override fun spawn() {
        // No visual elements - just for hit detection
    }

    override fun destroy() {}

    override fun onHoverChanged() {
        parent.onTabHoverChanged(tabIndex, isHovered)
    }
}
