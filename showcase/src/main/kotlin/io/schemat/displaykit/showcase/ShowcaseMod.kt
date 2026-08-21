package io.schemat.displaykit.showcase

import com.mojang.brigadier.arguments.StringArgumentType
import io.schemat.displaykit.fabric.FabricDisplayKit
import io.schemat.displaykit.fabric.text.Chat
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteIndex
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
                            .executes { ctx ->
                                val p = ctx.source.player
                                if (p == null) {
                                    ctx.source.sendFailure(Component.literal("Picker requires a player"))
                                    0
                                } else {
                                    // TEMPORARY DIAGNOSTIC: Minecraft's command
                                    // dispatcher reports "an unexpected error" and
                                    // logs through an appender whose buffer we could
                                    // not flush. Write the trace straight to a file.
                                    try {
                                        PickerWindow.open(p)
                                    } catch (t: Throwable) {
                                        java.io.File("/tmp/dk-picker-error.txt")
                                            .writeText(t.stackTraceToString())
                                        p.sendSystemMessage(
                                            Component.literal("picker failed: ${t::class.java.simpleName}: ${t.message}")
                                        )
                                        throw t
                                    }
                                    1
                                }
                            }
                    )
                    .then(
                        Commands.literal("closepicker")
                            .executes { ctx ->
                                ctx.source.player?.let { PickerWindow.closeFor(it.uuid) }
                                1
                            }
                    )
            )
        }

        logger.info("DisplayKit Showcase initialized ({} demos)", demos.size)
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
