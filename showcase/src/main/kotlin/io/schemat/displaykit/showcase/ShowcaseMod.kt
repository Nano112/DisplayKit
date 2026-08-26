package io.schemat.displaykit.showcase

import com.mojang.brigadier.arguments.StringArgumentType
import io.schemat.displaykit.fabric.FabricDisplayKit
import io.schemat.displaykit.fabric.input.TerminalChatCapture
import io.schemat.displaykit.fabric.thread.ServerThreadDispatcher
import io.schemat.displaykit.fabric.text.Chat
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.RenderMode
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import io.schemat.displaykit.sprite.SpriteGlyphs
import org.slf4j.LoggerFactory

/**
 * Acceptance harness for DisplayKit's sprite primitives.
 *
 * DisplayKit is already a loadable mod but registers no commands, so nothing
 * in it is reachable without a consumer. This module is that consumer. It
 * lives outside the library so consumers do not inherit a `/dk` command tree
 * they never asked for.
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

        // One lifecycle boundary owns every showcase session. Individual
        // demos stay focused on composition and cannot forget disconnect or
        // shutdown cleanup when a new window type is added.
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            val playerId = handler.player.uuid
            ServerThreadDispatcher.dispatch(server) { closePlayerSessions(playerId) }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            server.playerList.players.map { it.uuid }.forEach(::closePlayerSessions)
            TerminalChatCapture.router = null
        }

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
                        Commands.literal("pickerboxes").executes { ctx ->
                            val p = ctx.source.player ?: return@executes 0
                            PickerWindow.describeBoxes(p.uuid).forEach { logger.info("box {}", it) }
                            1
                        }
                    )
                    .then(
                        // Log a stack every time a named sprite mints a glyph
                        // variant. `glyphstats` says WHICH sprite is drawn at
                        // more geometries than it was warmed at; this says
                        // WHERE from, which is the part no counter can give.
                        Commands.literal("glyphtrace")
                            .then(
                                Commands.argument("id", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val id = StringArgumentType.getString(ctx, "id")
                                        SpriteGlyphs.traceId = if (id == "off") null else id
                                        ctx.source.sendSuccess(
                                            { Component.literal("glyph trace: ${SpriteGlyphs.traceId ?: "off"}") },
                                            false
                                        )
                                        1
                                    }
                            )
                    )
                    .then(
                        // How many glyph variants each sprite is carrying.
                        //
                        // A leak warning names the ONE variant a paint just
                        // minted, which does not say whether warm-up is
                        // working. A sprite with one variant is warmed
                        // correctly; a sprite with five has been warmed at
                        // five different geometries, and the pack is carrying
                        // four copies of it for nothing.
                        Commands.literal("glyphstats").executes { ctx ->
                            val all = SpriteGlyphs.requested()
                            val byId = all.groupBy { it.entry.id }
                            val worst = byId.entries.sortedByDescending { it.value.size }.take(3)
                            val hist = byId.values.groupingBy { it.size }.eachCount().toSortedMap()
                            logger.info(
                                "glyphstats: {} variants across {} sprites; " +
                                    "variants-per-sprite histogram {}; worst {}",
                                all.size,
                                byId.size,
                                hist,
                                worst.map { "${it.key} x${it.value.size} " +
                                    it.value.map { v -> "a=${v.ascent}/h=${v.renderHeight}" } }
                            )
                            ctx.source.sendSuccess(
                                { Component.literal("${all.size} variants / ${byId.size} sprites, see log") },
                                false
                            )
                            1
                        }
                    )
                    .then(
                        // Aim the player at a picker tab, server-side.
                        //
                        // Automated clicking could not be driven from the
                        // client: locating the panel by pixel heuristics
                        // picked up night-time terrain, and a guessed look
                        // angle missed -- which reads as "nothing happened"
                        // and is indistinguishable from a pass. The server
                        // knows exactly where the tab is.
                        Commands.literal("aimtab")
                            .then(
                                Commands.argument("atlas", StringArgumentType.word())
                                    .executes { ctx ->
                                        aimAtTab(
                                            ctx.source,
                                            StringArgumentType.getString(ctx, "atlas"),
                                            null
                                        )
                                    }
                                    .then(
                                        // Stand square in front of it, for a
                                        // head-on screenshot: at an angle a
                                        // horizontal border is not a
                                        // horizontal row of pixels and cannot
                                        // be measured.
                                        Commands.argument("distance", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.5, 32.0))
                                            .executes { ctx ->
                                                aimAtTab(
                                                    ctx.source,
                                                    StringArgumentType.getString(ctx, "atlas"),
                                                    com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "distance")
                                                )
                                            }
                                    )
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
                        Commands.literal("map")
                            .executes { ctx -> openCanvas(ctx.source, CanvasWindows.Kind.MAP) }
                    )
                    .then(
                        Commands.literal("skills")
                            .executes { ctx -> openCanvas(ctx.source, CanvasWindows.Kind.SKILLS) }
                    )
                    .then(
                        Commands.literal("tabs")
                            .executes { ctx -> openCanvas(ctx.source, CanvasWindows.Kind.TABS) }
                    )
                    .then(
                        Commands.literal("closecanvas")
                            .executes { ctx ->
                                ctx.source.player?.let { CanvasWindows.closeFor(it.uuid) }
                                1
                            }
                    )
                    .then(
                        Commands.literal("aimcanvas")
                            .then(
                                Commands.argument("node", StringArgumentType.word())
                                    .executes { ctx ->
                                        aimAtCanvasNode(
                                            ctx.source,
                                            StringArgumentType.getString(ctx, "node")
                                        )
                                    }
                            )
                    )
                    .then(
                        // Measurement target, not a demo. Renders the same
                        // sprite pattern in either mode so the two captures
                        // can be diffed -- see CalibrationWindow's KDoc for
                        // why there is no hand-built reference mark.
                        Commands.literal("calib")
                            .then(
                                Commands.argument("index", IntegerArgumentType.integer(0, 99))
                                    .executes { ctx ->
                                        openCalibration(
                                            ctx.source, RenderMode.COMPOSITED,
                                            IntegerArgumentType.getInteger(ctx, "index")
                                        )
                                    }
                                    .then(
                                        Commands.literal("nopack").executes { ctx ->
                                            openCalibration(
                                                ctx.source, RenderMode.ENTITIES,
                                                IntegerArgumentType.getInteger(ctx, "index")
                                            )
                                        }
                                    )
                            )
                    )
                    .then(
                        Commands.literal("closecalib")
                            .executes { ctx ->
                                ctx.source.player?.let { CalibrationWindow.closeFor(it.uuid) }
                                1
                            }
                    )
                    .then(
                        Commands.literal("terminal")
                            .executes { ctx -> openTerminal(ctx.source) }
                    )
                    .then(
                        Commands.literal("toolbar")
                            .executes { ctx ->
                                val player = ctx.source.player ?: return@executes 0
                                openWindow(player, "toolbar") { ToolbarDemo.open(player) }
                            }
                    )
                    .then(
                        Commands.literal("toolbarsurface")
                            .executes { ctx ->
                                val player = ctx.source.player ?: return@executes 0
                                openWindow(player, "surface toolbar") { ToolbarDemo.openSurface(player) }
                            }
                    )
                    .then(
                        Commands.literal("properties")
                            .executes { ctx ->
                                val player = ctx.source.player ?: return@executes 0
                                openWindow(player, "properties") { PropertySheetDemo.open(player) }
                            }
                    )
                    .then(
                        Commands.literal("closeproperties")
                            .executes { ctx ->
                                ctx.source.player?.let { PropertySheetDemo.closeFor(it.uuid) }
                                1
                            }
                    )
                    .then(
                        Commands.literal("closetoolbar")
                            .executes { ctx ->
                                ctx.source.player?.let {
                                    ToolbarDemo.closeFor(it.uuid)
                                    ToolbarDemo.closeSurfaceFor(it.uuid)
                                }
                                1
                            }
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

    private fun closePlayerSessions(playerId: java.util.UUID) {
        PickerWindow.closeFor(playerId)
        TerminalWindow.closeFor(playerId)
        CanvasWindows.closeFor(playerId)
        CalibrationWindow.closeFor(playerId)
        PropertySheetDemo.closeFor(playerId)
        ToolbarDemo.closeFor(playerId)
        ToolbarDemo.closeSurfaceFor(playerId)
    }

    private fun openPicker(source: CommandSourceStack, renderMode: RenderMode): Int {
        val p = source.player
        if (p == null) {
            source.sendFailure(Component.literal("Picker requires a player"))
            return 0
        }
        return openWindow(p, "picker") { PickerWindow.open(p, renderMode) }
    }

    private fun openCanvas(source: CommandSourceStack, kind: CanvasWindows.Kind): Int {
        val player = source.player ?: run {
            source.sendFailure(Component.literal("Canvas window requires a player"))
            return 0
        }
        return openWindow(player, kind.name.lowercase()) {
            when (kind) {
                CanvasWindows.Kind.MAP -> CanvasWindows.openMap(player)
                CanvasWindows.Kind.SKILLS -> CanvasWindows.openSkills(player)
                CanvasWindows.Kind.TABS -> CanvasWindows.openTabs(player)
            }
        }
    }

    private fun aimAtCanvasNode(source: CommandSourceStack, nodeId: String): Int {
        val player = source.player ?: return 0
        val result = CanvasWindows.aimAt(player.uuid, nodeId) ?: run {
            source.sendFailure(Component.literal("No visible canvas node '$nodeId'"))
            return 0
        }
        logger.info(
            "aimcanvas {} -> yaw {} pitch {}, ray lands on {}",
            nodeId, result.yaw, result.pitch, result.landedOn ?: "NOTHING"
        )
        source.sendSuccess(
            { Component.literal("aimed at $nodeId, ray lands on ${result.landedOn ?: "NOTHING"}") },
            false
        )
        return if (result.landedOn != null) 1 else 0
    }


    /** Point the player at a picker tab so an automated click can land on it. */
    private fun aimAtTab(source: CommandSourceStack, atlas: String, faceFrom: Double?): Int {
        val p = source.player ?: run {
            source.sendFailure(Component.literal("aimtab requires a player"))
            return 0
        }
        val r = (
            if (faceFrom != null) PickerWindow.faceRegion(p.uuid, "tab-$atlas", faceFrom)
            else PickerWindow.aimAt(p.uuid, "tab-$atlas")
            ) ?: run {
            source.sendFailure(
                Component.literal("no region 'tab-$atlas' -- is the picker open?")
            )
            return 0
        }
        // Logged, not just chatted: an automated check reads the server log,
        // and "aimed but landed on nothing" is the failure worth seeing.
        logger.info(
            "aimtab {} -> yaw {} pitch {}, ray lands on {}",
            atlas, r.yaw, r.pitch, r.landedOn ?: "NOTHING"
        )
        source.sendSuccess(
            { Component.literal("aimed at tab-$atlas, ray lands on ${r.landedOn ?: "NOTHING"}") },
            false
        )
        return if (r.landedOn == "tab-$atlas") 1 else 0
    }

    private fun openCalibration(source: CommandSourceStack, renderMode: RenderMode, index: Int): Int {
        val p = source.player
        if (p == null) {
            source.sendFailure(Component.literal("Calibration requires a player"))
            return 0
        }
        return openWindow(p, "calibration") { CalibrationWindow.open(p, renderMode, index) }
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
