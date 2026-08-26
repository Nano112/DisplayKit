package io.schemat.displaykit.fabric.pack

import io.schemat.displaykit.pack.GeistFontProvider
import io.schemat.displaykit.pack.ItemModelAssetProvider
import io.schemat.displaykit.pack.PackConfig
import io.schemat.displaykit.pack.PackManager
import io.schemat.displaykit.pack.SpriteAssetProvider
import io.schemat.displaykit.sprite.SpriteDiagnostics
import io.schemat.displaykit.fabric.thread.ServerThreadDispatcher
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Logger

/**
 * Fabric integration for DisplayKit resource pack distribution.
 *
 * Handles:
 * - Pack server lifecycle tied to Minecraft server
 * - Automatic pack distribution on player join
 * - Pack status tracking
 */
object FabricPackIntegration {
    private val LOGGER = LoggerFactory.getLogger("DisplayKit-Pack")
    private var packManager: PackManager? = null
    private var server: MinecraftServer? = null
    private val expectedPackIds = ConcurrentHashMap<UUID, UUID>()
    private val packHashes = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, ByteArray>>()
    // JOIN fires before Minecraft has necessarily inserted the player into
    // PlayerList. Keep the actual connection so the first push never depends
    // on that timing detail; DISCONNECT and shutdown remove the reference.
    private val connectedPlayers = ConcurrentHashMap<UUID, ServerPlayer>()
    private val packWaitGenerations = ConcurrentHashMap<UUID, Long>()
    private val pendingResends = PackResendQueue()
    private var presentationWaitTimeoutMillis = PackConfig().presentationWaitTimeoutMillis

    /**
     * Initialize the pack system.
     * Call this during server starting.
     */
    fun initialize(minecraftServer: MinecraftServer, config: PackConfig = PackConfig()) {
        LOGGER.info("Initializing DisplayKit resource pack system...")

        server = minecraftServer
        presentationWaitTimeoutMillis = config.presentationWaitTimeoutMillis

        val javaLogger = Logger.getLogger("DisplayKit-Pack")
        packManager = PackManager(config, javaLogger).apply {
            // Register asset providers
            if (config.registerGeistFont) {
                registerAssetProvider(GeistFontProvider)
            }
            registerAssetProvider(ItemModelAssetProvider)
            // SpriteAssetProvider is opt-in, register manually via:
            // FabricPackIntegration.registerAssetProvider(SpriteAssetProvider)

            // Set up callbacks for Fabric
            onSendPack = { playerId, url, sha1 ->
                sendPackToPlayer(playerId, url, sha1)
            }

            onPackReady = {
                LOGGER.info("Resource pack ready at: ${getPackUrl()}")
            }

            // Initialize (starts HTTP server and builds pack)
            initialize()
        }

        LOGGER.info("DisplayKit resource pack system initialized")
    }

    /**
     * Shutdown the pack system.
     * Call this during server stopping.
     */
    fun shutdown() {
        LOGGER.info("Shutting down DisplayKit resource pack system...")
        packManager?.shutdown()
        packManager = null
        server = null
        expectedPackIds.clear()
        packHashes.clear()
        connectedPlayers.clear()
        awaitingPack.clear()
        packWaitGenerations.clear()
        pendingResends.clear()
    }

    /**
     * Called when a player joins.
     */
    fun onPlayerJoin(player: ServerPlayer) {
        val pm = packManager ?: return
        if (!pm.isAutoSendOnJoin()) return
        connectedPlayers[player.uuid] = player
        if (pm.sendToPlayer(player.uuid)) {
            LOGGER.info("Sent resource pack to ${player.name.string}")
        }
    }

    /**
     * Called when a player leaves.
     */
    fun onPlayerLeave(playerId: UUID) {
        expectedPackIds.remove(playerId)
        packHashes.remove(playerId)
        connectedPlayers.remove(playerId)
        awaitingPack.remove(playerId)
        packWaitGenerations.remove(playerId)
        pendingResends.remove(playerId)
        packManager?.onPlayerLeave(playerId)
    }

    /**
     * Manually send the pack to a player.
     */
    fun sendPack(player: ServerPlayer): Boolean {
        connectedPlayers[player.uuid] = player
        return packManager?.sendToPlayer(player.uuid) == true
    }

    /**
     * Rebuild the pack (e.g., after adding new assets).
     */
    fun rebuildPack() {
        packManager?.rebuildPack()
    }

    /** Callbacks waiting for a player to finish applying the current pack. */
    private val awaitingPack = ConcurrentHashMap<UUID, ConcurrentLinkedQueue<() -> Unit>>()

    /**
     * Rebuild the pack and resend to all online players.
     */
    fun rebuildAndResendToAll() {
        rebuildPack()
        val mcServer = server ?: return
        for (player in mcServer.playerList.players) {
            if (pendingResends.request(player.uuid, packManager?.getPlayerStatus(player.uuid))) {
                sendPack(player)
            } else {
                LOGGER.debug(
                    "Coalescing rebuilt pack for {} behind the artifact already in flight",
                    player.uuid
                )
            }
        }
    }

    /**
     * Run [action] once [playerId] has applied the pack that is being sent
     * now — or immediately, if nothing is in flight for them.
     *
     * Spawning a glyph-composed surface before its pack lands renders every
     * new codepoint as a missing-glyph box, whose advance is the font's
     * default rather than the sprite's. The row widths are then wrong, so the
     * measured text block is wrong, and every layer centres somewhere
     * different — a scattered window that silently fixes itself the next time
     * it is opened.
     */
    fun whenPackApplied(playerId: UUID, action: () -> Unit) {
        val pm = packManager
        val mcServer = server
        if (pm == null || mcServer == null || pm.getPlayerStatus(playerId) != PackManager.PackStatus.SENDING) {
            action()
            return
        }
        awaitingPack.computeIfAbsent(playerId) { ConcurrentLinkedQueue() }.add(action)
        armPackWaitDeadline(playerId, mcServer)
    }

    /** Start (or renew) the response deadline for the current/latest push. */
    private fun armPackWaitDeadline(playerId: UUID, mcServer: MinecraftServer) {
        if (!awaitingPack.containsKey(playerId)) return
        val waitGeneration = packWaitGenerations.compute(playerId) { _, current ->
            (current ?: 0L) + 1L
        }!!

        // A client that never answers must not leave the caller waiting
        // forever -- that turns a cosmetic race into a window that simply
        // never appears. Fire anyway after a grace period; a tofu surface is
        // recoverable, an invisible one is not.
        val deadline = presentationWaitTimeoutMillis
        Thread.ofVirtual().start {
            Thread.sleep(deadline)
            // A newer waiter belongs to a newer/current transfer and owns its
            // own full grace period. An older timer must not fail it early.
            if (!packWaitGenerations.remove(playerId, waitGeneration)) return@start
            val stranded = awaitingPack.remove(playerId) ?: return@start
            expectedPackIds.remove(playerId)
            packHashes.remove(playerId)
            pendingResends.remove(playerId)
            packManager?.failInFlight(playerId)
            SpriteDiagnostics.warnOnce(
                "pack-wait-timeout:$playerId",
                "Client $playerId did not report applying the resource pack within " +
                    "${deadline}ms. Opening anyway -- sprite glyphs may render as " +
                    "missing-glyph boxes until the pack lands."
            )
            ServerThreadDispatcher.dispatch(mcServer) { stranded.forEach { it() } }
        }
    }

    /**
     * The client's answer to a pack push, from `ResourcePackResponseMixin`.
     *
     * Only terminal states release the waiters. ACCEPTED and DOWNLOADED are
     * progress reports — the pack is not in use yet — so waking on those would
     * reintroduce exactly the race this exists to close.
     */
    fun onPackResponse(playerId: UUID, packId: UUID, action: String) {
        val hashes = packHashes[playerId]
        val sha1 = hashes?.get(packId) ?: run {
            LOGGER.debug("Ignoring resource-pack response {} for unknown pack {}", action, packId)
            return
        }
        // Consent, download, and reload are distinct phases. Any progress
        // proves the client is alive, so renew the full quiet-period deadline
        // instead of failing a valid but slow resource reload.
        if (action == "ACCEPTED" || action == "DOWNLOADED") {
            server?.let { armPackWaitDeadline(playerId, it) }
            return
        }
        val status = when (action) {
            "SUCCESSFULLY_LOADED" -> PackManager.PackStatus.ACCEPTED
            "DECLINED" -> PackManager.PackStatus.DECLINED
            "FAILED_DOWNLOAD", "FAILED_RELOAD", "INVALID_URL", "DISCARDED" ->
                PackManager.PackStatus.FAILED
            else -> return
        }
        hashes.remove(packId)
        if (hashes.isEmpty()) packHashes.remove(playerId, hashes)
        val current = expectedPackIds.remove(playerId, packId)
        val managerCurrent = packManager?.onPackStatus(playerId, sha1, status) ?: current
        if (!current || !managerCurrent) {
            LOGGER.debug(
                "Released resource-pack response {} for stale pack {} without waking current waiters",
                action, packId
            )
            return
        }
        // A pack rebuild requested while this artifact was awaiting consent
        // is sent only now. Keep every presentation waiter asleep until that
        // newest immutable artifact reaches a terminal state, and give it a
        // fresh full deadline instead of inheriting the first push's clock.
        if (pendingResends.take(playerId)) {
            val player = connectedPlayers[playerId]
                ?: server?.playerList?.getPlayer(playerId)
            if (player != null && sendPack(player)) {
                server?.let { armPackWaitDeadline(playerId, it) }
                return
            }
        }
        // Release waiters even on failure: a surface that renders as tofu is
        // still better than one that never appears, and SpriteDiagnostics
        // already reports the degraded path.
        packWaitGenerations.remove(playerId)
        val waiting = awaitingPack.remove(playerId) ?: return
        val mcServer = server ?: return
        // Back onto the server thread: entity spawning is not thread-safe.
        // If shutdown won the race, the dispatcher deliberately drops these
        // presentation callbacks because their owning scopes are already gone.
        ServerThreadDispatcher.dispatch(mcServer) { waiting.forEach { it() } }
    }

    /**
     * Get the pack manager for direct access.
     */
    fun getPackManager(): PackManager? = packManager

    /**
     * Register an asset provider.
     */
    fun registerAssetProvider(provider: io.schemat.displaykit.pack.AssetProvider) {
        packManager?.registerAssetProvider(provider)
    }

    private fun sendPackToPlayer(playerId: UUID, url: String, sha1: ByteArray) {
        val mcServer = server ?: throw IllegalStateException("Minecraft server is not available")

        val player = connectedPlayers[playerId]
            ?: mcServer.playerList.getPlayer(playerId)
            ?: throw IllegalStateException("Player $playerId is not connected")

        try {
            val sha1Hex = sha1.joinToString("") { "%02x".format(it) }
            val packId = UUID.nameUUIDFromBytes(sha1)
            expectedPackIds[playerId] = packId
            packHashes.computeIfAbsent(playerId) { java.util.concurrent.ConcurrentHashMap() }[packId] = sha1.copyOf()
            val packet = ClientboundResourcePackPushPacket(
                packId, url, sha1Hex, false,
                Optional.of(Component.literal("DisplayKit UI Enhancement Pack"))
            )
            player.connection.send(packet)
            LOGGER.debug("Sent resource pack to ${player.name.string}")
            packManager?.onPackStatus(playerId, PackManager.PackStatus.SENDING)
        } catch (e: Exception) {
            val failedId = UUID.nameUUIDFromBytes(sha1)
            expectedPackIds.remove(playerId, failedId)
            packHashes[playerId]?.remove(failedId)
            throw e
        }
    }

    /**
     * Handle resource pack status response from client.
     * Call this from a mixin or event handler that intercepts the status packet.
     */
    fun onPackStatus(playerId: UUID, accepted: Boolean) {
        val status = if (accepted) {
            PackManager.PackStatus.ACCEPTED
        } else {
            PackManager.PackStatus.DECLINED
        }
        val expected = expectedPackIds.remove(playerId)
        val sha1 = expected?.let { packHashes[playerId]?.remove(it) }
        if (sha1 == null) packManager?.onPackStatus(playerId, status)
        else packManager?.onPackStatus(playerId, sha1, status)
        LOGGER.debug("Player $playerId pack status: $status")
    }
}
