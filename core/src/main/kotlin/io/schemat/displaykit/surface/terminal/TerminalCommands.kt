package io.schemat.displaykit.surface.terminal

/**
 * The terminal's built-in commands — enough to make it demonstrably a
 * terminal and not just an echo box.
 *
 * [execute] always echoes the raw input first (`"> $input"`), THEN applies
 * whatever effect a recognised command has — so `clear`'s own echo line is
 * itself wiped by the clear it triggers, matching a real terminal's `clear`.
 * Anything not recognised just stays as that echo line: "everything else
 * echoes back as `> <input>`".
 */
object TerminalCommands {

    private val KNOWN = listOf("clear", "help")

    /** Run one line of terminal input against [model]. */
    fun execute(model: TerminalModel, input: String) {
        model.append("> $input")
        when (input.trim().lowercase()) {
            "clear" -> model.clear()
            "help" -> model.append("commands: ${KNOWN.joinToString(", ")}")
            // Anything else: the echo above is the whole response.
        }
    }
}
