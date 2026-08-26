package io.schemat.displaykit.pack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

/**
 * Simple HTTP server for serving resource packs to Minecraft clients.
 *
 * Uses Java's built-in HttpServer for zero external dependencies.
 * Pack pushes use immutable, hash-addressed URLs. The legacy `/pack.zip`
 * endpoint remains as a convenience alias for the current pack, but must not
 * be used in a Minecraft push: a rebuild during download would otherwise pair
 * the old advertised SHA-1 with new bytes.
 */
class PackServer(
    private val config: PackConfig,
    private val logger: Logger = Logger.getLogger("PackServer")
) {
    @Volatile private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private var boundPort: Int? = null
    private val snapshots = mutableMapOf<String, ByteArray>()
    private val pins = mutableMapOf<String, Int>()
    private var currentHash: String? = null

    data class PackArtifact(val url: String, val sha1: ByteArray)

    /**
     * Start the HTTP server.
     */
    @Synchronized
    fun start() {
        if (server != null) {
            logger.warning("PackServer already running")
            return
        }

        val pool = Executors.newFixedThreadPool(2) { task ->
            Thread.ofPlatform().daemon(true).name("displaykit-pack-http").unstarted(task)
        }
        var http: HttpServer? = null
        try {
            http = HttpServer.create(
                InetSocketAddress(config.bindAddress, config.port),
                0
            ).apply {
                createContext("/pack.zip", ::handlePackRequest)
                createContext("/packs/", ::handleVersionedPackRequest)
                createContext("/health", ::handleHealthCheck)
                executor = pool
                start()
            }
            executor = pool
            server = http
            boundPort = http.address.port
        } catch (failure: Throwable) {
            http?.stop(0)
            pool.shutdownNow()
            throw IllegalStateException(
                "Could not bind DisplayKit pack server to ${config.bindAddress}:${config.port}. " +
                    "Choose another port with PackConfig or -Ddisplaykit.pack.port.",
                failure,
            )
        }

        logger.info("PackServer started at ${getPackUrl()}")
    }

    /**
     * Stop the HTTP server.
     */
    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
        boundPort = null
        snapshots.clear()
        pins.clear()
        currentHash = null
        logger.info("PackServer stopped")
    }

    /**
     * Update the pack being served.
     */
    @Synchronized
    fun updatePack(bytes: ByteArray, sha1: ByteArray) {
        val computed = PackBuilder.computeSha1(bytes)
        require(computed.contentEquals(sha1)) { "Pack SHA-1 does not match its bytes" }

        val hash = PackBuilder.sha1ToHex(sha1)
        val previous = currentHash
        snapshots.putIfAbsent(hash, bytes.copyOf())
        currentHash = hash
        prune(previous)
        logger.info("Pack updated: ${bytes.size} bytes, SHA-1: $hash")
    }

    /**
     * Get the URL where the pack is served.
     */
    @Synchronized
    fun getPackUrl(): String = currentHash?.let(::versionedUrl) ?: legacyUrl()

    /**
     * Get the SHA-1 hash of the current pack.
     */
    @Synchronized
    fun getPackSha1(): ByteArray? = currentHash?.let(::hexToBytes)

    /** Pin and return the current immutable artifact for an in-flight client. */
    @Synchronized
    fun acquireCurrentPack(): PackArtifact? {
        val hash = currentHash ?: return null
        pins[hash] = (pins[hash] ?: 0) + 1
        return PackArtifact(versionedUrl(hash), hexToBytes(hash))
    }

    /** Release a previously acquired artifact. Current bytes remain available. */
    @Synchronized
    fun releasePack(sha1: ByteArray) {
        val hash = PackBuilder.sha1ToHex(sha1)
        val count = pins[hash] ?: return
        if (count <= 1) pins.remove(hash) else pins[hash] = count - 1
        prune(hash)
    }

    /**
     * Check if the server is running.
     */
    fun isRunning(): Boolean = server != null

    private fun handlePackRequest(exchange: HttpExchange) {
        val bytes = synchronized(this) { currentHash?.let(snapshots::get) }
        servePack(exchange, bytes, immutable = false)
    }

    private fun handleVersionedPackRequest(exchange: HttpExchange) {
        val hash = exchange.requestURI.path
            .removePrefix("/packs/")
            .removeSuffix(".zip")
            .takeIf { it.length == 40 && it.all { char -> char.digitToIntOrNull(16) != null } }
        val bytes = synchronized(this) { hash?.lowercase()?.let(snapshots::get) }
        servePack(exchange, bytes, immutable = true)
    }

    private fun servePack(exchange: HttpExchange, bytes: ByteArray?, immutable: Boolean) {
        try {
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1)
                return
            }

            exchange.responseHeaders.apply {
                add("Content-Type", "application/zip")
                add("Content-Disposition", "attachment; filename=\"displaykit.zip\"")
                if (immutable) {
                    add("Cache-Control", "public, max-age=31536000, immutable")
                } else {
                    add("Cache-Control", "no-cache, no-store, must-revalidate")
                    add("Pragma", "no-cache")
                    add("Expires", "0")
                }
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
            val packSize = synchronized(this) { currentHash?.let(snapshots::get)?.size ?: 0 }
            val response = """{"status":"ok","packSize":$packSize}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        } catch (e: Exception) {
            logger.severe("Error in health check: ${e.message}")
        } finally {
            exchange.close()
        }
    }

    @Synchronized
    internal fun snapshotCount(): Int = snapshots.size

    private fun legacyUrl(): String = urlFor("/pack.zip")

    private fun versionedUrl(hash: String): String =
        urlFor("/packs/$hash.zip")

    private fun urlFor(path: String): String = URI(
        "http",
        null,
        config.publicAddress,
        boundPort ?: config.port,
        path,
        null,
        null,
    ).toASCIIString()

    private fun prune(hash: String?) {
        if (hash != null && hash != currentHash && (pins[hash] ?: 0) == 0) snapshots.remove(hash)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
