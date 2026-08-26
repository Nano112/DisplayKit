package io.schemat.displaykit.fabric

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.animation.AnimationTicker
import io.schemat.displaykit.fabric.input.FabricTextInput
import io.schemat.displaykit.fabric.input.HotbarScrollCapture
import io.schemat.displaykit.fabric.interaction.FabricInteractionHandler
import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.fabric.packet.FabricPacketSender
import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.fabric.scheduler.FabricScheduler
import io.schemat.displaykit.fabric.state.BlockStateResolver
import io.schemat.displaykit.fabric.thread.ServerThreadDispatcher
import io.schemat.displaykit.pack.PackConfig
import io.schemat.displaykit.platform.TaskHandle
import io.schemat.displaykit.sprite.SpriteDiagnostics
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.fabric.pack.PackSync
import io.schemat.displaykit.surface.SurfaceFocus
import io.schemat.displaykit.surface.SurfaceRepaintGuard
import io.schemat.displaykit.ui.InteractionRouter
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.util.logging.Logger

class FabricDisplayKit : ModInitializer {

    companion object {
        const val MOD_ID = "displaykit"
        private val LOGGER = LoggerFactory.getLogger(MOD_ID)

        lateinit var instance: FabricDisplayKit
            private set

        // Static so mods can set this before DisplayKit's entrypoint runs
        var enableResourcePack: Boolean = false
        var sharedPackConfig: PackConfig = PackConfig()

        /**
         * Whether by-reference sprite glyphs are usable right now.
         *
         * `SpriteGlyphs`/`SpriteCanvas`/`SpriteGlyphs`-backed rendering (via
         * `SpriteFontProvider` and `SpacingFontProvider`) only works once the
         * DisplayKit resource pack has been pushed to the client, which is
         * gated on [enableResourcePack]. There is no layout-preserving
         * fallback for a canvas without its fonts — consumers that want to
         * degrade gracefully (e.g. `GridMapTab`) must branch on this rather
         * than assume the compositor is always available.
         */
        val glyphsAvailable: Boolean get() = enableResourcePack
    }

    var server: MinecraftServer? = null
        private set

    private var scheduler: FabricScheduler? = null
    private var interactionHandler: FabricInteractionHandler? = null
    private var animationTickTask: TaskHandle? = null
    var textInput: FabricTextInput? = null
        private set

    // Instance accessors delegate to companion for backwards compat
    var packConfig: PackConfig
        get() = sharedPackConfig
        set(value) { sharedPackConfig = value }

    override fun onInitialize() {
        instance = this
        LOGGER.info("[DisplayKit] Initializing DisplayKit Fabric module...")

        io.schemat.displaykit.fabric.hotbar.HotbarMenu.register()

        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            this.server = server
            BlockStateResolver.init(server)

            val logger = Logger.getLogger(MOD_ID)
            val fabricScheduler = FabricScheduler(server)
            val packetSender = FabricPacketSender(server)
            val fabricTextInput = FabricTextInput()

            scheduler = fabricScheduler
            textInput = fabricTextInput

            val platform = FabricPlatformProvider(
                server = server,
                logger = logger,
                scheduler = fabricScheduler,
                packetSender = packetSender,
                textInput = fabricTextInput
            )

            DisplayKit.init(platform)

            // AUTO may choose COMPOSITED only when its generated glyphs can
            // actually reach clients. Installing a slice source while the
            // pack pipeline is disabled made AUTO silently emit tofu glyphs.
            io.schemat.displaykit.surface.SliceGlyphSource.installed =
                if (enableResourcePack) {
                    io.schemat.displaykit.fabric.surface.FabricSliceGlyphSource
                } else {
                    null
                }

            SpriteDiagnostics.checkVersion(
                SpriteIndex.bundled,
                server.serverVersion
            )
            if (!enableResourcePack) SpriteDiagnostics.packDisabled()

            interactionHandler = FabricInteractionHandler(server)
            interactionHandler?.register()

            // Initialize resource pack system
            if (enableResourcePack) {
                FabricPackIntegration.initialize(server, packConfig)
            }

            // Start animation ticker (runs every tick)
            animationTickTask = fabricScheduler.scheduleRepeating(1L, 1L, Runnable {
                AnimationTicker.tick()
            })

            io.schemat.displaykit.fabric.surface.SurfaceTicker.register()

            LOGGER.info("[DisplayKit] Initialized on Fabric")
        }

        ServerLifecycleEvents.SERVER_STOPPED.register {
            // Stop animation ticker
            animationTickTask?.cancel()
            animationTickTask = null
            AnimationTicker.clear()

            // Shutdown resource pack system
            if (enableResourcePack) {
                FabricPackIntegration.shutdown()
            }

            io.schemat.displaykit.surface.SliceGlyphSource.installed = null

            DisplayKit.shutdown()
            scheduler?.shutdown()
            scheduler = null
            interactionHandler = null
            textInput = null
            this.server = null
        }

        // SERVER_STOPPING is guaranteed to run on Minecraft's owner thread,
        // before connection teardown can start firing from Netty. Close all
        // owner-thread state here; later DISCONNECT callbacks become small,
        // idempotent per-player cleanup rather than the primary shutdown path.
        ServerLifecycleEvents.SERVER_STOPPING.register {
            textInput?.cancelAll()
            InteractionRouter.closeAll()
            HotbarScrollCapture.clear()
        }

        // Send resource pack to players on join
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            if (enableResourcePack) {
                FabricPackIntegration.onPlayerJoin(handler.player)
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            val playerId = handler.player.uuid
            // Fabric may fire DISCONNECT from the Netty event loop on an
            // abrupt socket close. Surface hosts and StateScope are explicitly
            // server-thread-owned, so all teardown crosses that boundary here.
            ServerThreadDispatcher.dispatch(server) {
                textInput?.cancelInput(playerId)
                InteractionRouter.cleanupPlayer(playerId)
                // The connection is already going away, so there is nobody to
                // send a corrective held-slot packet to -- drop the remembered
                // slot rather than trying to restore it. The focus-cleared
                // listener below covers the still-connected case.
                HotbarScrollCapture.forget(playerId)
                if (enableResourcePack) {
                    FabricPackIntegration.onPlayerLeave(playerId)
                }
            }
        }

        // Losing surface focus ends any scroll capture that focus was arming.
        // Registered once, here, rather than by each window: the same
        // per-window duplication is what left this unwired in the first
        // place. A still-connected player gets their real hotbar slot back;
        // one whose player object has gone just has the memory dropped.
        SurfaceFocus.onFocusCleared { playerId ->
            val player = this.server?.playerList?.getPlayer(playerId)
            if (player != null) HotbarScrollCapture.release(player)
            else HotbarScrollCapture.forget(playerId)
        }

        // Let a hover repaint resend the pack if it allocated new glyphs.
        // Without this a hover-dependent widget whose pre-warm missed a state
        // pushes codepoints the client has never seen, and they render as
        // missing-glyph boxes -- silently, with nothing in either log. The
        // leak warning that comes with the rebuild is the point as much as
        // the rebuild is: it names the pre-warm that needs fixing.
        SurfaceRepaintGuard.guard = { paint ->
            PackSync.withPackSync("surface-hover", paint)
        }
    }

    fun getPlayerRef(uuid: java.util.UUID): FabricPlayerRef? {
        val server = this.server ?: return null
        val player = server.playerList.getPlayer(uuid) ?: return null
        return FabricPlayerRef(player)
    }

    /**
     * Get the pack integration for direct access.
     */
    fun getPackIntegration(): FabricPackIntegration? {
        return if (enableResourcePack) FabricPackIntegration else null
    }

    /**
     * Rebuild and redistribute the resource pack.
     */
    fun rebuildPack() {
        if (enableResourcePack) {
            FabricPackIntegration.rebuildPack()
        }
    }
}
