package io.schemat.displaykit.pack

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Manages resource pack lifecycle and distribution.
 *
 * This is a platform-agnostic manager. Platform-specific implementations
 * (Fabric, Paper) should integrate with this via the callbacks.
 */
class PackManager(
    private val config: PackConfig,
    private val logger: Logger = Logger.getLogger("PackManager")
) {
    private val server = PackServer(config, logger)
    private val builder = PackBuilder(config)

    // Track player pack status
    private val playerStatus = ConcurrentHashMap<UUID, PackStatus>()
    private val playerPacks = ConcurrentHashMap<UUID, ConcurrentHashMap<String, ByteArray>>()
    private val latestPlayerPack = ConcurrentHashMap<UUID, String>()

    // Asset providers that contribute to the pack
    private val assetProviders = mutableListOf<AssetProvider>()

    // Callbacks for platform integration
    var onSendPack: ((playerId: UUID, url: String, sha1: ByteArray) -> Unit)? = null
    var onPackReady: (() -> Unit)? = null

    enum class PackStatus {
        PENDING,
        SENDING,
        ACCEPTED,
        DECLINED,
        FAILED
    }

    /**
     * Initialize and start the pack server.
     */
    fun initialize() {
        logger.info("Initializing PackManager...")
        server.start()
        rebuildPack()
        logger.info("PackManager initialized")
    }

    /**
     * Shutdown the pack server.
     */
    fun shutdown() {
        logger.info("Shutting down PackManager...")
        server.stop()
        playerStatus.clear()
        playerPacks.clear()
        latestPlayerPack.clear()
        logger.info("PackManager shutdown complete")
    }

    /**
     * Register an asset provider.
     *
     * Idempotent: registering the same provider instance twice is a no-op the
     * second time. Callers (demos, commands re-run per invocation) may call
     * this unconditionally on every use without growing the provider list or
     * re-running the same contribution on every subsequent [rebuildPack].
     */
    fun registerAssetProvider(provider: AssetProvider) {
        if (provider in assetProviders) {
            logger.fine("Asset provider already registered, skipping: ${provider.javaClass.simpleName}")
            return
        }
        assetProviders.add(provider)
        logger.fine("Registered asset provider: ${provider.javaClass.simpleName}")
    }

    /**
     * Rebuild the pack from all asset providers.
     */
    fun rebuildPack() {
        logger.info("Rebuilding resource pack...")

        builder.clear()

        // Let all providers contribute their assets
        for (provider in assetProviders) {
            provider.contributeAssets(builder)
        }

        // Build and update server
        val packBytes = builder.build()
        val sha1 = PackBuilder.computeSha1(packBytes)
        server.updatePack(packBytes, sha1)

        logger.info("Pack rebuilt: ${packBytes.size} bytes")
        onPackReady?.invoke()
    }

    /**
     * Send pack to a player.
     */
    fun sendToPlayer(playerId: UUID): Boolean {
        val artifact = server.acquireCurrentPack()
        if (artifact == null) {
            logger.warning("Cannot send pack to $playerId: no pack available")
            return false
        }

        val hash = PackBuilder.sha1ToHex(artifact.sha1)
        val transfers = playerPacks.computeIfAbsent(playerId) { ConcurrentHashMap() }
        if (transfers.putIfAbsent(hash, artifact.sha1.copyOf()) != null) {
            // The same immutable artifact is already in flight. Coalesce the
            // duplicate request: a second packet would use the same pack id,
            // making its responses indistinguishable from the first push.
            server.releasePack(artifact.sha1)
            logger.fine("Pack $hash is already in flight for $playerId")
            return true
        }
        latestPlayerPack[playerId] = hash
        playerStatus[playerId] = PackStatus.SENDING
        val sender = onSendPack
        if (sender == null) {
            onPackStatus(playerId, artifact.sha1, PackStatus.FAILED)
            logger.warning("Cannot send pack to $playerId: no platform sender installed")
            return false
        }
        try {
            sender(playerId, artifact.url, artifact.sha1.copyOf())
        } catch (failure: Throwable) {
            onPackStatus(playerId, artifact.sha1, PackStatus.FAILED)
            logger.log(Level.WARNING, "Failed to send pack to $playerId", failure)
            return false
        }
        logger.fine("Sending pack to $playerId")
        return true
    }

    /**
     * Handle pack status update from client.
     */
    fun onPackStatus(playerId: UUID, status: PackStatus) {
        val latest = latestPlayerPack[playerId]
        if (latest == null || status == PackStatus.SENDING) {
            playerStatus[playerId] = status
            logger.fine("Player $playerId pack status: $status")
            return
        }
        onPackStatus(playerId, hexToBytes(latest), status)
    }

    /**
     * Complete one specific immutable pack transfer.
     *
     * Returns true only when this was the player's newest push. A stale
     * response still releases its snapshot, but cannot mark a newer transfer
     * complete or wake UI waiters.
     */
    fun onPackStatus(playerId: UUID, sha1: ByteArray, status: PackStatus): Boolean {
        val hash = PackBuilder.sha1ToHex(sha1)
        if (status != PackStatus.SENDING) {
            val transfers = playerPacks[playerId]
            transfers?.remove(hash)?.let(server::releasePack)
            if (transfers != null && transfers.isEmpty()) playerPacks.remove(playerId, transfers)
        }
        val current = latestPlayerPack[playerId] == hash
        if (current) {
            playerStatus[playerId] = status
            if (status != PackStatus.SENDING) latestPlayerPack.remove(playerId, hash)
        }
        logger.fine("Player $playerId pack $hash status: $status${if (current) "" else " (stale)"}")
        return current
    }

    /**
     * Called when a player joins.
     */
    fun onPlayerJoin(playerId: UUID) {
        if (config.autoSendOnJoin) {
            sendToPlayer(playerId)
        }
    }

    /**
     * Called when a player leaves.
     */
    fun onPlayerLeave(playerId: UUID) {
        playerStatus.remove(playerId)
        latestPlayerPack.remove(playerId)
        playerPacks.remove(playerId)?.values?.forEach(server::releasePack)
    }

    /**
     * Fail and release every transfer still pinned for [playerId].
     *
     * Platform adapters should call this after their response deadline. Late
     * client packets are then harmless stale responses instead of retaining
     * every prior pack rebuild for the lifetime of the connection.
     */
    fun failInFlight(playerId: UUID) {
        latestPlayerPack.remove(playerId)
        playerPacks.remove(playerId)?.values?.forEach(server::releasePack)
        playerStatus[playerId] = PackStatus.FAILED
    }

    /**
     * Get pack status for a player.
     */
    fun getPlayerStatus(playerId: UUID): PackStatus? = playerStatus[playerId]

    /**
     * Get the pack URL.
     */
    fun getPackUrl(): String = server.getPackUrl()

    /**
     * Get the pack SHA1 hash.
     */
    fun getPackSha1(): ByteArray? = server.getPackSha1()

    /**
     * Whether auto-send on join is enabled.
     */
    fun isAutoSendOnJoin(): Boolean = config.autoSendOnJoin

    /**
     * Check if the server is running.
     */
    fun isRunning(): Boolean = server.isRunning()

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

/**
 * Interface for components that contribute assets to the resource pack.
 */
interface AssetProvider {
    /**
     * Add assets to the pack builder.
     */
    fun contributeAssets(builder: PackBuilder)
}
