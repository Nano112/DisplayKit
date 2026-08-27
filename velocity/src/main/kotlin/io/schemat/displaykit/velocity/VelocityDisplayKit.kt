package io.schemat.displaykit.velocity

import com.github.retrooper.packetevents.PacketEventsAPI
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.animation.AnimationTicker
import io.schemat.displaykit.surface.SliceGlyphSource
import io.schemat.displaykit.surface.SurfaceFocus
import io.schemat.displaykit.ui.InteractionRouter
import io.schemat.displaykit.velocity.input.HotbarScrollRestore
import io.schemat.displaykit.velocity.input.InputPacketListener
import io.schemat.displaykit.velocity.input.PlayerPositionCache
import io.schemat.displaykit.velocity.input.VelocityTextInputStub
import io.schemat.displaykit.velocity.packet.DisplayMetadataEncoder
import io.schemat.displaykit.velocity.packet.PacketEventsSender
import io.schemat.displaykit.velocity.player.VelocityPlayerRef
import io.schemat.displaykit.velocity.scheduler.VelocityDkScheduler
import io.schemat.displaykit.velocity.state.VelocityBlockStateResolver
import io.schemat.displaykit.velocity.thread.UiOwnerThread
import io.schemat.displaykit.velocity.tick.UiTicker
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange
import java.util.UUID
import java.util.logging.Logger

/**
 * Configuration handed to [VelocityDisplayKit.init] by the hosting plugin.
 *
 * The module is a library inside somebody else's Velocity plugin: the proxy,
 * the PacketEvents instance and the logger are all the host's, and the
 * PacketEvents listener is registered on the host's API in the window between
 * its load() and init().
 */
class VelocityDisplayKitConfig(
    val proxy: ProxyServer,
    val packetEvents: PacketEventsAPI<*>,
    val logger: Logger = Logger.getLogger("DisplayKit"),
    val enableResourcePack: Boolean = false,
)

/**
 * Entrypoint and lifecycle owner for DisplayKit on a Velocity proxy.
 *
 * Everything runs packet-only through PacketEvents: no world data, no real
 * entities. Rendering is pack-free ENTITIES mode; the resource-pack pipeline
 * (COMPOSITED mode) is not ported yet and requesting it logs a warning and
 * continues without it, which RenderMode.AUTO turns into ENTITIES rendering
 * on its own.
 */
class VelocityDisplayKit private constructor(
    val config: VelocityDisplayKitConfig,
    val uiThread: UiOwnerThread,
    val positions: PlayerPositionCache,
    val versionGate: VersionGate,
    private val ticker: UiTicker,
    private val inputListener: PacketListenerCommon,
    private val scroll: HotbarScrollRestore,
) {

    fun playerRef(player: Player): VelocityPlayerRef =
        VelocityPlayerRef(config.proxy, positions, player)

    fun playerRef(playerId: UUID): VelocityPlayerRef? =
        config.proxy.getPlayer(playerId).orElse(null)?.let(::playerRef)

    companion object {
        @Volatile
        private var instance: VelocityDisplayKit? = null

        @JvmStatic
        fun instance(): VelocityDisplayKit? = instance

        @JvmStatic
        fun requireInstance(): VelocityDisplayKit =
            checkNotNull(instance) { "VelocityDisplayKit.init has not been called" }

        /**
         * Initialises the platform. Call between the host's PacketEvents
         * load() and init(), so the input listener joins the same
         * registration window as the host's own listeners.
         */
        @JvmStatic
        fun init(config: VelocityDisplayKitConfig): VelocityDisplayKit {
            check(instance == null) { "VelocityDisplayKit is already initialised" }

            if (config.enableResourcePack) {
                config.logger.warning(
                    "The resource-pack pipeline is not ported to Velocity yet; " +
                        "continuing pack-free, surfaces render in ENTITIES mode"
                )
            }

            // A half-initialised platform is worse than none: each step
            // registers its undo so a failure anywhere leaves no global
            // installed, and a consumer that degrades gracefully or retries
            // starts from a clean slate.
            val rollback = ArrayDeque<() -> Unit>()
            try {
                val created = build(config, rollback)
                instance = created
                return created
            } catch (t: Throwable) {
                while (rollback.isNotEmpty()) {
                    runCatching { rollback.removeLast().invoke() }
                }
                instance = null
                throw t
            }
        }

        private fun build(
            config: VelocityDisplayKitConfig,
            rollback: ArrayDeque<() -> Unit>,
        ): VelocityDisplayKit {
            val uiThread = UiOwnerThread()
            rollback.addLast { uiThread.close() }
            val positions = PlayerPositionCache()
            val scheduler = VelocityDkScheduler(uiThread)
            val encoder = DisplayMetadataEncoder(VelocityBlockStateResolver(config.logger))
            val versionGate = VersionGate(config.proxy, config.packetEvents)

            val sender = PacketEventsSender(
                config.proxy, config.packetEvents, encoder,
                viewerFilter = versionGate::isSupported,
            )

            val platform = VelocityPlatformProvider(
                proxy = config.proxy,
                positions = positions,
                logger = config.logger,
                scheduler = scheduler,
                packetSender = sender,
                textInput = VelocityTextInputStub(config.logger),
            )
            DisplayKit.init(platform)
            rollback.addLast {
                DisplayKit.shutdown()
                SliceGlyphSource.installed = null
            }

            val scroll = HotbarScrollRestore(
                dispatchToUiThread = { task -> uiThread.dispatch(task) },
                sendHeldSlot = { playerId, slot ->
                    config.proxy.getPlayer(playerId).ifPresent { player ->
                        config.packetEvents.playerManager.getUser(player)
                            ?.sendPacketSilently(WrapperPlayServerHeldItemChange(slot))
                    }
                },
            )

            SurfaceFocus.onFocusCleared { playerId -> scroll.release(playerId) }

            val listener = InputPacketListener(
                positions = positions,
                scroll = scroll,
                dispatchToUiThread = { task -> uiThread.dispatch(task) },
                onPlayerRemoved = { },
            )
            config.packetEvents.eventManager.registerListener(listener)
            rollback.addLast { config.packetEvents.eventManager.unregisterListener(listener) }

            val ticker = UiTicker(config.proxy, uiThread, config.logger)
            ticker.start()
            rollback.addLast { ticker.close() }

            config.logger.info("DisplayKit Velocity platform initialised (pack-free, ENTITIES render mode)")
            return VelocityDisplayKit(config, uiThread, positions, versionGate, ticker, listener, scroll)
        }

        /** Tears down in the reverse order of init. */
        @JvmStatic
        fun shutdown() {
            val current = instance ?: return
            instance = null

            current.ticker.close()
            // Unregister before tearing the thread down: the listener
            // dispatches onto it, and a packet arriving mid-shutdown would
            // otherwise queue work that can never run
            runCatching { current.config.packetEvents.eventManager.unregisterListener(current.inputListener) }
            current.uiThread.dispatch {
                AnimationTicker.clear()
                InteractionRouter.closeAll()
            }
            current.positions.clear()
            current.scroll.clear()
            current.uiThread.close()
            SliceGlyphSource.installed = null
            DisplayKit.shutdown()
            current.config.logger.info("DisplayKit Velocity platform shut down")
        }
    }
}
