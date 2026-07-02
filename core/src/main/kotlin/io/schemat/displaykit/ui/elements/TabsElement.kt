package io.schemat.displaykit.ui.elements

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.*
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.UIElement

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
    val labelScale: Float = DEFAULT_LABEL_SCALE,
    val labelPadding: Float = DEFAULT_TAB_PADDING,
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

    /** Center x offset and width for each tab, in UI units. */
    data class TabSlot(val centerX: Float, val width: Float)

    companion object {
        const val DEFAULT_LABEL_SCALE = 0.35f

        /** Horizontal padding inside a tab, each side, in UI units. */
        const val DEFAULT_TAB_PADDING = 0.10f

        /**
         * Pure layout: each tab is at least [minWidth] wide, growing to fit
         * its label plus padding so labels can never crowd or overlap.
         */
        fun computeLayout(
            labels: List<String>,
            minWidth: Float,
            gap: Float,
            labelScale: Float = DEFAULT_LABEL_SCALE,
            padding: Float = DEFAULT_TAB_PADDING
        ): List<TabSlot> {
            val widths = labels.map { label ->
                maxOf(minWidth, TextMetrics.blockWidth(label, labelScale) + 2 * padding)
            }
            val total = widths.sum() + gap * (labels.size - 1).coerceAtLeast(0)
            var cursor = -total / 2
            return widths.map { w ->
                val slot = TabSlot(cursor + w / 2, w)
                cursor += w + gap
                slot
            }
        }
    }

    private val slots: List<TabSlot> by lazy {
        computeLayout(tabs.map { labelTextOf(it) }, tabWidth, gap, labelScale, labelPadding)
    }

    val totalWidth: Float
        get() = slots.sumOf { it.width.toDouble() }.toFloat() + gap * (tabs.size - 1).coerceAtLeast(0)

    private fun labelTextOf(tab: TabDefinition): String =
        if (tab.icon != null) "${tab.icon}${tab.label}" else tab.label

    override fun spawn() {
        val pos = ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z)

        tabs.forEachIndexed { index, tab ->
            val slot = slots[index]

            // Tab background
            val bg = VirtualBlockDisplay().apply {
                position = pos
                brightness = Brightness.FULL
                blockState = getTabMaterial(index)
                transformation = ui.buildUIElementMatrix(
                    localOffsetX = slot.centerX - slot.width / 2, localOffsetY = -tabHeight / 2, localOffsetZ = 0f,
                    scaleX = slot.width, scaleY = tabHeight, scaleZ = 0.02f
                )
            }
            tabBackgrounds.add(bg)
            spawnEntity(bg)

            // Tab label: FIXED in the panel plane so it stays glued to its
            // background from any view angle (billboarded labels parallax
            // against their plane-fixed highlight), vertically centered on
            // the tab (text_display blocks are bottom-anchored natively).
            val labelText = labelTextOf(tab)
            val labelPos = ui.calculatePosition(localOffset.x + slot.centerX, localOffset.y, localOffset.z - 0.03)
            val label = VirtualTextDisplay().apply {
                position = labelPos
                text = TextComponent.of(labelText)
                billboard = Billboard.FIXED
                backgroundColor = DkColor.TRANSPARENT
                isSeeThrough = false
                brightness = Brightness.FULL
                textAlignment = TextAlignment.CENTER
                viewRange = 1.0f
                transformation = ui.buildUIElementMatrix(
                    localOffsetX = 0f,
                    localOffsetY = TextMetrics.verticalCenterCorrection(labelText, labelScale),
                    localOffsetZ = 0f,
                    scaleX = labelScale, scaleY = labelScale, scaleZ = labelScale
                )
            }
            tabLabels.add(label)
            spawnEntity(label)

            // Tab button (invisible hit target), centered on the visual tab
            val tabButton = TabButtonElement(
                ui = ui,
                localOffset = Vec3d(localOffset.x + slot.centerX, localOffset.y, localOffset.z),
                tabIndex = index, tabWidth = slot.width, tabHeight = tabHeight,
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
            display.text = TextComponent.of(labelTextOf(tab))
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
