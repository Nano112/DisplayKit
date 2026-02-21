package io.schemat.displaykit.pack

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
        logger.info("PackManager shutdown complete")
    }

    /**
     * Register an asset provider.
     */
    fun registerAssetProvider(provider: AssetProvider) {
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
    fun sendToPlayer(playerId: UUID) {
        val sha1 = server.getPackSha1()
        if (sha1 == null) {
            logger.warning("Cannot send pack to $playerId: no pack available")
            return
        }

        playerStatus[playerId] = PackStatus.SENDING
        onSendPack?.invoke(playerId, server.getPackUrl(), sha1)
        logger.fine("Sending pack to $playerId")
    }

    /**
     * Handle pack status update from client.
     */
    fun onPackStatus(playerId: UUID, status: PackStatus) {
        playerStatus[playerId] = status
        logger.fine("Player $playerId pack status: $status")
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
