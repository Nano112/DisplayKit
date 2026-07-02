package io.schemat.displaykit.fabric.pack

import io.schemat.displaykit.pack.DefaultAssets
import io.schemat.displaykit.pack.GeistFontProvider
import io.schemat.displaykit.pack.ItemModelAssetProvider
import io.schemat.displaykit.pack.PackConfig
import io.schemat.displaykit.pack.PackManager
import io.schemat.displaykit.pack.SpriteAssetProvider
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.util.Optional
import java.util.UUID
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

    /**
     * Initialize the pack system.
     * Call this during server starting.
     */
    fun initialize(minecraftServer: MinecraftServer, config: PackConfig = PackConfig()) {
        LOGGER.info("Initializing DisplayKit resource pack system...")

        server = minecraftServer

        val javaLogger = Logger.getLogger("DisplayKit-Pack")
        packManager = PackManager(config, javaLogger).apply {
            // Register asset providers
            if (config.registerDefaultProviders) {
                registerAssetProvider(DefaultAssets)
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
    }

    /**
     * Called when a player joins.
     */
    fun onPlayerJoin(player: ServerPlayer) {
        val pm = packManager ?: return
        if (!pm.isAutoSendOnJoin()) return

        val sha1 = pm.getPackSha1() ?: return
        val url = pm.getPackUrl()
        val sha1Hex = sha1.joinToString("") { "%02x".format(it) }
        val packId = UUID.nameUUIDFromBytes(sha1)

        try {
            val packet = ClientboundResourcePackPushPacket(
                packId, url, sha1Hex, false,
                Optional.of(Component.literal("DisplayKit UI Enhancement Pack"))
            )
            player.connection.send(packet)
            LOGGER.info("Sent resource pack to ${player.name.string}")
            pm.onPackStatus(player.uuid, PackManager.PackStatus.SENDING)
        } catch (e: Exception) {
            LOGGER.error("Failed to send resource pack to ${player.name.string}: ${e.message}", e)
            pm.onPackStatus(player.uuid, PackManager.PackStatus.FAILED)
        }
    }

    /**
     * Called when a player leaves.
     */
    fun onPlayerLeave(playerId: UUID) {
        packManager?.onPlayerLeave(playerId)
    }

    /**
     * Manually send the pack to a player.
     */
    fun sendPack(player: ServerPlayer) {
        packManager?.sendToPlayer(player.uuid)
    }

    /**
     * Rebuild the pack (e.g., after adding new assets).
     */
    fun rebuildPack() {
        packManager?.rebuildPack()
    }

    /**
     * Rebuild the pack and resend to all online players.
     */
    fun rebuildAndResendToAll() {
        rebuildPack()
        val mcServer = server ?: return
        for (player in mcServer.playerList.players) {
            sendPack(player)
        }
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
        val mcServer = server ?: run {
            LOGGER.warn("Cannot send pack to $playerId: server not available")
            return
        }

        val player = mcServer.playerList.getPlayer(playerId) ?: run {
            LOGGER.warn("Cannot send pack to $playerId: player not found")
            return
        }

        try {
            val sha1Hex = sha1.joinToString("") { "%02x".format(it) }
            val packId = UUID.nameUUIDFromBytes(sha1)
            val packet = ClientboundResourcePackPushPacket(
                packId, url, sha1Hex, false,
                Optional.of(Component.literal("DisplayKit UI Enhancement Pack"))
            )
            player.connection.send(packet)
            LOGGER.debug("Sent resource pack to ${player.name.string}")
            packManager?.onPackStatus(playerId, PackManager.PackStatus.SENDING)
        } catch (e: Exception) {
            LOGGER.error("Failed to send resource pack to ${player.name.string}: ${e.message}", e)
            packManager?.onPackStatus(playerId, PackManager.PackStatus.FAILED)
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
        packManager?.onPackStatus(playerId, status)
        LOGGER.debug("Player $playerId pack status: $status")
    }
}
