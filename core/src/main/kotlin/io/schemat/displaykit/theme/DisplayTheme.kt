package io.schemat.displaykit.theme

import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.surface.BlockButton
import io.schemat.displaykit.surface.widget.ActionMenuViewStyle
import io.schemat.displaykit.surface.widget.PropertySheetViewStyle
import io.schemat.displaykit.surface.widget.SurfaceWindowStyle

data class ThemeColors(
    val background: DkColor,
    val surface: DkColor,
    val raised: DkColor,
    val hover: DkColor,
    val text: DkColor,
    val muted: DkColor,
    val dim: DkColor,
    val accent: DkColor,
    val accentDark: DkColor,
    val secondary: DkColor,
    val secondaryDark: DkColor,
    val success: DkColor,
    val warning: DkColor,
    val danger: DkColor,
    val info: DkColor,
)

data class ThemeSpacing(
    val xs: Int = 2,
    val sm: Int = 4,
    val md: Int = 8,
    val lg: Int = 12,
    val xl: Int = 20,
) {
    init {
        require(listOf(xs, sm, md, lg, xl).zipWithNext().all { (a, b) -> a <= b } && xs >= 0) {
            "Theme spacing must be non-negative and ascending"
        }
    }
}

data class ThemeTypography(
    val compactLineHeight: Int = 9,
    val bodyLineHeight: Int = 10,
    val titleMinHeight: Int = 24,
) {
    init {
        require(compactLineHeight > 0 && bodyLineHeight >= compactLineHeight && titleMinHeight >= bodyLineHeight) {
            "Theme typography heights must be positive and ascending"
        }
    }
}

data class ThemeControls(
    val standardWidth: Int = BlockButton.widthFor(BlockButton.MIN_WIDTH),
    val wideWidth: Int = BlockButton.widthFor(300),
    val buttonHeight: Int = BlockButton.HEIGHT,
    val baseThickness: Float = 0.0625f,
) {
    init {
        require(standardWidth >= BlockButton.MIN_WIDTH && wideWidth >= standardWidth) {
            "Theme control widths must fit DisplayKit button artwork"
        }
        require(buttonHeight >= BlockButton.HEIGHT) { "Theme button height must fit its artwork" }
        require(baseThickness > 0f) { "Theme control thickness must be positive" }
    }
}

data class ThemeMaterials(
    val backing: BlockStateRef = BlockStateRef.BLACK_CONCRETE,
    val button: BlockStateRef = BlockStateRef("minecraft:polished_blackstone"),
    val selectedButton: BlockStateRef = BlockStateRef("minecraft:gilded_blackstone"),
    val disabledButton: BlockStateRef = BlockStateRef.GRAY_CONCRETE,
    val navigationButton: BlockStateRef = BlockStateRef("minecraft:deepslate_tiles"),
    val dangerButton: BlockStateRef = BlockStateRef("minecraft:red_concrete"),
)

data class ThemeAssets(
    val windowFrame: SpriteId = SpriteId("gui", "tooltip/background"),
)

/** Logical elevations; the renderer remains the sole owner of physical Z. */
data class ThemeDepth(
    val content: Int = 0,
    val raised: Int = 1,
    val tooltip: Int = 2,
) {
    init {
        require(content >= 0 && raised > content && tooltip > raised) {
            "Theme depth roles must be strictly ordered elevations"
        }
    }
}

data class ThemeMotion(
    val interpolationTicks: Int = 2,
) {
    init { require(interpolationTicks in 0..59) { "Theme interpolation must be 0..59 ticks" } }
}

/** Complete appearance policy; geometry still comes from measured primitives. */
data class DisplayTheme(
    val colors: ThemeColors,
    val spacing: ThemeSpacing = ThemeSpacing(),
    val typography: ThemeTypography = ThemeTypography(),
    val controls: ThemeControls = ThemeControls(),
    val materials: ThemeMaterials = ThemeMaterials(),
    val assets: ThemeAssets = ThemeAssets(),
    val depth: ThemeDepth = ThemeDepth(),
    val motion: ThemeMotion = ThemeMotion(),
) {
    fun windowStyle(): SurfaceWindowStyle = SurfaceWindowStyle(
        frameId = assets.windowFrame,
        padding = spacing.md,
        columnGap = spacing.sm,
        bodyGap = spacing.md,
        titleMinHeight = typography.titleMinHeight,
        backdrop = colors.background,
        backingBlock = materials.backing,
    )

    @JvmOverloads
    fun actionMenuStyle(width: Int = controls.standardWidth, pageSize: Int = 6): ActionMenuViewStyle =
        ActionMenuViewStyle(
            width = width,
            pageSize = pageSize,
            gap = spacing.xs,
            buttonBase = materials.button,
            selectedBase = materials.selectedButton,
            disabledBase = materials.disabledButton,
            navigationBase = materials.navigationButton,
            baseThickness = controls.baseThickness,
        )

    @JvmOverloads
    fun propertySheetStyle(width: Int = controls.standardWidth): PropertySheetViewStyle =
        PropertySheetViewStyle(
            width = width,
            gap = spacing.xs,
            fieldGap = spacing.sm,
            buttonBase = materials.button,
            disabledBase = materials.disabledButton,
            baseThickness = controls.baseThickness,
            labelColor = colors.text,
            valueColor = colors.muted,
        )

    companion object {
        @JvmField
        val VANILLA_DARK = DisplayTheme(
            colors = ThemeColors(
                background = DkColor.fromRGB(18, 19, 22),
                surface = DkColor.fromRGB(25, 27, 32),
                raised = DkColor.fromRGB(35, 38, 44),
                hover = DkColor.fromRGB(50, 54, 62),
                text = DkColor.WHITE,
                muted = DkColor.fromRGB(170, 170, 170),
                dim = DkColor.fromRGB(100, 100, 100),
                accent = DkColor.fromRGB(85, 255, 255),
                accentDark = DkColor.fromRGB(0, 140, 180),
                secondary = DkColor.fromRGB(170, 85, 255),
                secondaryDark = DkColor.fromRGB(100, 45, 160),
                success = DkColor.fromRGB(85, 255, 85),
                warning = DkColor.fromRGB(255, 255, 85),
                danger = DkColor.fromRGB(255, 85, 85),
                info = DkColor.fromRGB(85, 85, 255),
            )
        )
    }
}
