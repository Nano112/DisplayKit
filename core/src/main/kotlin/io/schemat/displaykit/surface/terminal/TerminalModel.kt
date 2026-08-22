package io.schemat.displaykit.surface.terminal

/**
 * An ordered scrollback of already-wrapped display rows.
 *
 * [append] wraps its input to [columns]-character rows on the MODEL side:
 * the canvas a terminal widget eventually renders into has no clipping (see
 * [io.schemat.displaykit.surface.layout.ScrollNode]'s KDoc), so a row that
 * measured wider than the pane would draw straight out through the frame
 * instead of being cut off. [lines] hands back a plain list of already-sized
 * rows, so the view never measures or re-wraps text itself.
 *
 * **Bounded, not infinite.** Capped at [capacity] rows (default 200): once
 * full, appending drops the OLDEST row for every new one that lands. A very
 * chatty terminal loses its earliest history — nobody should build a feature
 * on this scrollback remembering more than [capacity] rows.
 */
class TerminalModel(
    private val columns: Int,
    private val capacity: Int = DEFAULT_CAPACITY
) {
    init {
        require(columns > 0) { "columns must be positive, was $columns" }
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val rows = ArrayDeque<String>()

    /**
     * Append one logical line, wrapping it to [columns]-wide rows first and
     * evicting the oldest row(s) if [capacity] is exceeded.
     */
    fun append(line: String) {
        for (row in wrap(line, columns)) {
            rows.addLast(row)
            if (rows.size > capacity) rows.removeFirst()
        }
    }

    /** The current scrollback, oldest row first. */
    fun lines(): List<String> = rows.toList()

    /** Drop every row. */
    fun clear() = rows.clear()

    companion object {
        const val DEFAULT_CAPACITY = 200

        /**
         * Split [text] into rows of at most [columns] characters.
         *
         * A hard character wrap, not a word wrap: simpler, and — unlike a
         * word-aware wrapper, which can still overflow on one word longer
         * than the column count — it can never produce a row longer than
         * [columns] regardless of input, which is the one invariant the view
         * actually depends on. An empty string still produces one (empty)
         * row, so a blank [append] renders as a blank line rather than
         * vanishing entirely.
         */
        fun wrap(text: String, columns: Int): List<String> {
            require(columns > 0) { "columns must be positive, was $columns" }
            if (text.isEmpty()) return listOf("")
            val out = mutableListOf<String>()
            var i = 0
            while (i < text.length) {
                val end = minOf(i + columns, text.length)
                out += text.substring(i, end)
                i = end
            }
            return out
        }
    }
}
