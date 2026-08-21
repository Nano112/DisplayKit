package io.schemat.displaykit.tmpdiag

import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.*
import io.schemat.displaykit.surface.*
import kotlin.test.Test

class DumpDiag {
    @Test fun dump() {
        val slot = SpriteIndex.bundled.get(SpriteId("gui","container/slot"))!!
        println("### slot sprite = ${slot.width}x${slot.height}")
        val c = SpriteCanvas(346, 264)
        // mimic the picker's grid: 3 cells across, 2 rows, origin (150,30), step 20
        for (r in 0 until 2) for (col in 0 until 3) {
            c.draw(slot, 150 + col*20, 30 + r*20)
        }
        println("### items drawn: ${c.itemCount()}  positions=${c.itemPositions()}")
        val comp = c.toTextComponent()
        fun walk(t: io.schemat.displaykit.render.TextComponent, d: Int) {
            val txt = t.text.map { ch ->
                val cp = ch.code
                if (cp in 32..126) ch.toString() else "U+%04X".format(cp)
            }.joinToString("")
            if (txt.isNotEmpty() || t.font != null)
                println("  ".repeat(d) + "[font=${t.font ?: "-"}] '${txt}'")
            t.children.forEach { walk(it, d+1) }
        }
        walk(comp, 0)
        println("### maxRowAdvance=${c.maxRowAdvance()}  FONT_LINE_HEIGHT=${TextMetrics.FONT_LINE_HEIGHT_PX} TOP_BEARING=${TextMetrics.GLYPH_TOP_BEARING_PX}")
        println("### glyph variants requested: ${SpriteGlyphs.requested().size}")
        SpriteGlyphs.requested().take(8).forEach { println("   variant $it") }
    }
}
