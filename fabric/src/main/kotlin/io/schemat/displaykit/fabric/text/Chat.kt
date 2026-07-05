package io.schemat.displaykit.fabric.text

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import java.net.URI

/**
 * A small Kotlin DSL over the vanilla [Component] API for building consistent,
 * clickable server-UI chat — colors, hover tooltips, run/suggest buttons, and
 * clickable pagination.
 *
 * No Adventure dependency: on 1.21.6+ vanilla's own [ClickEvent]/[HoverEvent]
 * are sealed-interface records ([ClickEvent.RunCommand], [HoverEvent.ShowText],
 * …) that carry every affordance Adventure exposes over the same wire format.
 * Adventure adds MiniMessage string parsing and cross-platform serializers we
 * don't need on a Fabric server that already speaks vanilla components.
 *
 * Components carry mutable style, so every helper returns a FRESH instance —
 * never share one across messages.
 */
object Chat {

    // ── Brand palette (matches the HARDWIRED sidebar cyan) ──────────────────
    const val BRAND = 0x2FE0C0     // hardwired cyan/teal
    const val BRAND_DIM = 0x1C8C79
    const val ACCENT = 0xF5C542    // gold
    const val SUCCESS = 0x6FCF6F   // green
    const val ERROR = 0xE06C6C     // red
    const val MUTED = 0x8A8A8A     // gray
    const val TEXT = 0xE8E8E8      // near-white body

    // ── Leaf builders ───────────────────────────────────────────────────────

    fun text(s: String, color: Int = TEXT): MutableComponent =
        Component.literal(s).withStyle { it.withColor(color) }

    fun bold(s: String, color: Int = TEXT): MutableComponent =
        Component.literal(s).withStyle { it.withColor(color).withBold(true) }

    /** A section header: `━━ Title ━━` in brand color. */
    fun header(title: String): MutableComponent =
        Component.empty()
            .append(text("\n", MUTED))
            .append(text("▎", BRAND))
            .append(bold(" $title", BRAND))

    /** `key: value` on one line, muted key + bright value. */
    fun kv(key: String, value: String, valueColor: Int = TEXT): MutableComponent =
        Component.empty()
            .append(text("$key ", MUTED))
            .append(text(value, valueColor))

    /**
     * A clickable button `[label]` that RUNS [command] on click and shows
     * [hover] as a tooltip. Command must be a full command line WITHOUT the
     * leading slash-or-with — vanilla RunCommand wants the leading `/`.
     */
    fun button(
        label: String,
        command: String,
        hover: String? = null,
        color: Int = BRAND,
    ): MutableComponent = clickable("[$label]", ClickEvent.RunCommand(command), hover, color)

    /** Like [button] but pre-fills the chat box instead of running (for commands that need typing). */
    fun suggest(
        label: String,
        template: String,
        hover: String? = null,
        color: Int = ACCENT,
    ): MutableComponent = clickable("[$label]", ClickEvent.SuggestCommand(template), hover, color)

    /** A clickable external link. */
    fun link(label: String, url: String, color: Int = BRAND): MutableComponent =
        clickable(label, ClickEvent.OpenUrl(URI.create(url)), url, color)

    private fun clickable(
        label: String,
        click: ClickEvent,
        hover: String?,
        color: Int,
    ): MutableComponent = Component.literal(label).withStyle { st ->
        var s = st.withColor(color).withClickEvent(click)
        if (hover != null) s = s.withHoverEvent(HoverEvent.ShowText(text(hover, TEXT)))
        s
    }

    /** A colored `[tag]` chip (backend/status labels). */
    fun tag(label: String, color: Int): MutableComponent =
        Component.empty()
            .append(text("[", MUTED))
            .append(text(label, color))
            .append(text("]", MUTED))

    // ── Pagination ────────────────────────────────────────────────────────

    /**
     * Renders one page of [items] (each already a component line) followed by a
     * clickable `◀ page X/Y ▶` footer. [pageCommand] is a template containing
     * `{page}` which is substituted with the target page number (1-based).
     */
    fun paginate(
        items: List<Component>,
        page: Int,
        pageSize: Int,
        pageCommand: String,
    ): List<Component> {
        val total = ((items.size - 1).coerceAtLeast(0) / pageSize) + 1
        val p = page.coerceIn(1, total)
        val out = ArrayList<Component>()
        val from = (p - 1) * pageSize
        val to = (from + pageSize).coerceAtMost(items.size)
        for (i in from until to) out.add(items[i])
        if (total > 1) {
            val footer = Component.empty()
            footer.append(
                if (p > 1) button("◀", pageCommand.replace("{page}", (p - 1).toString()), "Previous page")
                else text("◀", MUTED)
            )
            footer.append(text("  page $p/$total  ", MUTED))
            footer.append(
                if (p < total) button("▶", pageCommand.replace("{page}", (p + 1).toString()), "Next page")
                else text("▶", MUTED)
            )
            out.add(footer)
        }
        return out
    }

    /** Convenience: `[HW]` brand prefix + a message component, one line. */
    fun line(body: Component): MutableComponent =
        Component.empty()
            .append(text("[", MUTED))
            .append(text("HW", BRAND))
            .append(text("] ", MUTED))
            .append(body)

    fun ok(msg: String): MutableComponent = line(text(msg, SUCCESS))
    fun err(msg: String): MutableComponent = line(text(msg, ERROR))
    fun info(msg: String): MutableComponent = line(text(msg, TEXT))
}
