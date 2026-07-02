package io.schemat.displaykit.pack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.logging.Logger

/**
 * Simple HTTP server for serving resource packs to Minecraft clients.
 *
 * Uses Java's built-in HttpServer for zero external dependencies.
 * Serves the pack at /pack.zip endpoint.
 */
class PackServer(
    private val config: PackConfig,
    private val logger: Logger = Logger.getLogger("PackServer")
) {
    private var server: HttpServer? = null
    private var packBytes: ByteArray? = null
    private var packSha1: ByteArray? = null

    /**
     * Start the HTTP server.
     */
    fun start() {
        if (server != null) {
            logger.warning("PackServer already running")
            return
        }

        server = HttpServer.create(
            InetSocketAddress(config.bindAddress, config.port),
            0
        ).apply {
            createContext("/pack.zip", ::handlePackRequest)
            createContext("/health", ::handleHealthCheck)
            executor = Executors.newFixedThreadPool(2)
            start()
        }

        logger.info("PackServer started at ${getPackUrl()}")
    }

    /**
     * Stop the HTTP server.
     */
    fun stop() {
        server?.stop(0)
        server = null
        logger.info("PackServer stopped")
    }

    /**
     * Update the pack being served.
     */
    fun updatePack(bytes: ByteArray, sha1: ByteArray) {
        this.packBytes = bytes
        this.packSha1 = sha1
        logger.info("Pack updated: ${bytes.size} bytes, SHA-1: ${PackBuilder.sha1ToHex(sha1)}")
    }

    /**
     * Get the URL where the pack is served.
     */
    fun getPackUrl(): String {
        return "http://${config.publicAddress}:${config.port}/pack.zip"
    }

    /**
     * Get the SHA-1 hash of the current pack.
     */
    fun getPackSha1(): ByteArray? = packSha1

    /**
     * Check if the server is running.
     */
    fun isRunning(): Boolean = server != null

    private fun handlePackRequest(exchange: HttpExchange) {
        try {
            val bytes = packBytes
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return
            }

            exchange.responseHeaders.apply {
                add("Content-Type", "application/zip")
                add("Content-Disposition", "attachment; filename=\"displaykit.zip\"")
                add("Cache-Control", "no-cache, no-store, must-revalidate")
                add("Pragma", "no-cache")
                add("Expires", "0")
            }

            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }

            logger.fine("Served pack to ${exchange.remoteAddress}")
        } catch (e: Exception) {
            logger.severe("Error serving pack: ${e.message}")
            try {
                exchange.sendResponseHeaders(500, -1)
            } catch (_: Exception) {}
        } finally {
            exchange.close()
        }
    }

    private fun handleHealthCheck(exchange: HttpExchange) {
        try {
            val response = """{"status":"ok","packSize":${packBytes?.size ?: 0}}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        } catch (e: Exception) {
            logger.severe("Error in health check: ${e.message}")
        } finally {
            exchange.close()
        }
    }
}
