package io.schemat.displaykit.showcase

import com.mojang.brigadier.arguments.StringArgumentType
import io.schemat.displaykit.fabric.FabricDisplayKit
import io.schemat.displaykit.fabric.text.Chat
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.RenderMode
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

/**
 * Acceptance harness for DisplayKit's sprite primitives.
 *
 * DisplayKit is already a loadable mod but registers no commands, so nothing
 * in it is reachable without a consumer. This module is that consumer. It
 * lives outside the library so `hardwired` and `blockbrains` do not inherit a
 * `/dk` command tree they never asked for.
 */
object ShowcaseMod : ModInitializer {

    private val logger = LoggerFactory.getLogger("DisplayKit-Showcase")

    private val demos = LinkedHashMap<String, (ServerPlayer) -> Unit>()

    /** Registered by each primitive's task; surfaced under `/dk demo <name>`. */
    fun registerDemo(name: String, handler: (ServerPlayer) -> Unit) {
        demos[name] = handler
    }

    override fun onInitialize() {
        logger.info("DisplayKit Showcase initializing")

        // The by-reference glyph mechanism needs the pack. This is in time:
        // the flag is read inside DisplayKit's SERVER_STARTING handler, which
        // fires after every mod's onInitialize.
        FabricDisplayKit.enableResourcePack = true

        Demos.registerAll()

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("dk")
                    .requires { Commands.LEVEL_GAMEMASTERS.check(it.permissions()) }
                    .then(
                        Commands.literal("sprite")
                            .then(
                                Commands.argument("query", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        querySprites(ctx.source, StringArgumentType.getString(ctx, "query"))
                                    }
                            )
                    )
                    .then(
                        Commands.literal("demo")
                            .then(
                                Commands.argument("name", StringArgumentType.word())
                                    .suggests { _, builder ->
                                        demos.keys.forEach(builder::suggest)
                                        builder.buildFuture()
                                    }
                                    .executes { ctx ->
                                        runDemo(ctx.source, StringArgumentType.getString(ctx, "name"))
                                    }
                            )
                    )
                    .then(
                        Commands.literal("picker")
                            .executes { ctx -> openPicker(ctx.source, RenderMode.AUTO) }
                            .then(
                                // Proves the sprite primitives work with ZERO
                                // resource pack: opens the same picker with
                                // renderMode = ENTITIES, so every sprite in
                                // the grid renders as vanilla's own
                                // atlas-sprite entity instead of a
                                // DisplayKit-generated glyph.
                                Commands.literal("nopack")
                                    .executes { ctx -> openPicker(ctx.source, RenderMode.ENTITIES) }
                            )
                    )
                    .then(
                        Commands.literal("closepicker")
                            .executes { ctx ->
                                ctx.source.player?.let { PickerWindow.closeFor(it.uuid) }
                                1
                            }
                    )
                    .then(
                        Commands.literal("terminal")
                            .executes { ctx -> openTerminal(ctx.source) }
                    )
                    .then(
                        Commands.literal("closeterminal")
                            .executes { ctx ->
                                ctx.source.player?.let { TerminalWindow.closeFor(it.uuid) }
                                1
                            }
                    )
            )
        }

        logger.info("DisplayKit Showcase initialized ({} demos)", demos.size)
    }

    private fun openPicker(source: CommandSourceStack, renderMode: RenderMode): Int {
        val p = source.player
        if (p == null) {
            source.sendFailure(Component.literal("Picker requires a player"))
            return 0
        }
        return openWindow(p, "picker") { PickerWindow.open(p, renderMode) }
    }

    /**
     * Run a window's open path, surfacing any failure to the player.
     *
     * Minecraft's command dispatcher catches whatever a command throws and
     * tells the player only "an unexpected error occurred", so without this
     * the actual exception is invisible in-game and easy to miss in the log.
     * The throw is preserved so the dispatcher still reports failure.
     */
    private fun openWindow(p: ServerPlayer, what: String, open: () -> Unit): Int {
        try {
            open()
        } catch (t: Throwable) {
            logger.error("/dk {} failed to open", what, t)
            p.sendSystemMessage(
                Component.literal("$what failed: ${t::class.java.simpleName}: ${t.message}")
            )
            throw t
        }
        return 1
    }

    private fun openTerminal(source: CommandSourceStack): Int {
        val p = source.player
        if (p == null) {
            source.sendFailure(Component.literal("Terminal requires a player"))
            return 0
        }
        return openWindow(p, "terminal") { TerminalWindow.open(p) }
    }

    private fun querySprites(source: CommandSourceStack, query: String): Int {
        val matches = SpriteIndex.bundled.find(query)
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("No sprite matches \"$query\""))
            return 0
        }

        source.sendSuccess(
            { Chat.header("${matches.size} sprite(s) matching \"$query\"") },
            false
        )
        for (entry in matches.take(20)) {
            source.sendSuccess({ describe(entry) }, false)
        }
        if (matches.size > 20) {
            source.sendSuccess(
                { Chat.info("...and ${matches.size - 20} more") },
                false
            )
        }
        return matches.size
    }

    private fun describe(entry: SpriteEntry): Component {
        val flags = buildList {
            if (entry.animated) add("animated")
            if (entry.greyscale) add("tintable")
            if (entry.nineSlice != null) add("nine-slice")
        }
        val suffix = if (flags.isEmpty()) "" else "  [${flags.joinToString(", ")}]"
        return Chat.kv(entry.id.toString(), "${entry.width}x${entry.height}$suffix")
    }

    private fun runDemo(source: CommandSourceStack, name: String): Int {
        val player = source.player ?: run {
            source.sendFailure(Component.literal("Demos require a player"))
            return 0
        }
        val demo = demos[name] ?: run {
            source.sendFailure(
                Component.literal("Unknown demo \"$name\". Available: ${demos.keys.joinToString(", ")}")
            )
            return 0
        }
        demo(player)
        return 1
    }
}
