package io.schemat.displaykit.surface

import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.sprite.SpriteIndex
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Chrome must be sized to tile exactly, or it visibly moires.
 *
 * The crops are fixed images, so a target that is not `border + k * tile`
 * cannot be filled exactly. The tiler covers the shortfall by putting its
 * LAST tile flush against the far edge, overlapping the previous one -- and
 * two copies of a patterned tile offset from each other read as diagonal
 * hatching, which is what every unselected picker tab showed once its height
 * was changed from 24 to 30 to suit the text grid.
 */
class NineSliceTilingTest {

    private fun sprite(name: String) =
        assertNotNull(SpriteIndex.bundled.get(SpriteId("gui", name)), "$name must be indexed")

    @Test
    fun theTabSpriteTilesAtItsNativeHeightAndNotAt30() {
        val tab = sprite("widget/tab")
        assertTrue(
            NineSliceLayout.tilesEvenly(tab, tab.width, tab.height),
            "a sprite must tile at its own native size"
        )
        assertTrue(
            !NineSliceLayout.tilesEvenly(tab, tab.width, 30),
            "30 must be rejected: its 22px centre leaves 28px of interior, so " +
                "tiles overlap and moire -- this is the bug that shipped"
        )
    }

    @Test
    fun exactSizeForAlwaysProducesATilingSize() {
        // The function that exists to get this right must actually get it
        // right, for every chrome sprite and a spread of requests.
        val names = listOf(
            "widget/tab", "widget/button", "widget/scroller",
            "widget/scroller_background", "tooltip/background"
        )
        for (name in names) {
            val e = sprite(name)
            for (wantW in listOf(1, 40, 130, 200, 350)) {
                for (wantH in listOf(1, 20, 24, 30, 100)) {
                    val (w, h) = NineSliceLayout.exactSizeFor(e, wantW, wantH)
                    assertTrue(
                        NineSliceLayout.tilesEvenly(e, w, h),
                        "$name sized ${w}x$h for request ${wantW}x$wantH does not tile evenly"
                    )
                    assertTrue(w >= wantW || w >= e.width, "$name width $w below request $wantW")
                    assertTrue(h >= wantH || h >= e.height, "$name height $h below request $wantH")
                }
            }
        }
    }

    @Test
    fun aFlatSpriteAlwaysTiles() {
        // No nine-slice metadata means nothing to tile, so the check must not
        // report a false problem for e.g. the close button.
        val cross = sprite("widget/cross_button")
        assertTrue(NineSliceLayout.tilesEvenly(cross, 14, 14))
        assertTrue(NineSliceLayout.tilesEvenly(cross, 99, 3))
    }

    @Test
    fun theOnlyTabHeightThatBothTilesAndCentresIsTooTallToUse() {
        // I asserted no such height existed. This test disproved that on its
        // first run -- 90 works: its interior is 88, exactly four 22px tiles,
        // and its label centres at 40, which is a row boundary.
        //
        // The conflict is therefore practical, not arithmetic. 90 is nearly
        // four times the sprite's native height; three tabs would be 270px in
        // a 264px panel. Within any usable size there is nothing, which is
        // still the argument for our own button art -- but stated as what it
        // is rather than as an impossibility.
        val tab = sprite("widget/tab")
        val pitch = io.schemat.displaykit.render.TextMetrics.FONT_LINE_HEIGHT_PX

        fun bothWork(h: Int): Boolean {
            if (!NineSliceLayout.tilesEvenly(tab, tab.width, h)) return false
            val want = (h - pitch) / 2
            return io.schemat.displaykit.render.TextMetrics.rowAlignedY(want) == want
        }

        assertTrue(bothWork(90), "90 is expected to both tile and centre")
        val usable = (tab.height..60).filter { bothWork(it) }
        assertTrue(
            usable.isEmpty(),
            "a usable height that both tiles and centres would be better than " +
                "the current compromise -- use it: $usable"
        )
    }
}

