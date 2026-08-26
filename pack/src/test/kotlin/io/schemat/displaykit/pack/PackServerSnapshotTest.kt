package io.schemat.displaykit.pack

import java.net.URI
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PackServerSnapshotTest {

    @Test
    fun inFlightHashAddressedPackSurvivesARebuild() {
        val server = server()
        server.start()
        try {
            val firstBytes = "first-pack".toByteArray()
            val firstHash = PackBuilder.computeSha1(firstBytes)
            server.updatePack(firstBytes, firstHash)
            val first = requireNotNull(server.acquireCurrentPack())

            val secondBytes = "second-pack".toByteArray()
            val secondHash = PackBuilder.computeSha1(secondBytes)
            server.updatePack(secondBytes, secondHash)
            val second = requireNotNull(server.acquireCurrentPack())

            assertNotEquals(first.url, second.url)
            assertTrue(first.url.contains(PackBuilder.sha1ToHex(firstHash)))
            assertTrue(second.url.contains(PackBuilder.sha1ToHex(secondHash)))
            assertContentEquals(firstBytes, download(first.url))
            assertContentEquals(secondBytes, download(second.url))
            assertEquals(2, server.snapshotCount())

            server.releasePack(first.sha1)
            assertEquals(1, server.snapshotCount(), "an unpinned non-current snapshot must be reclaimed")
            server.releasePack(second.sha1)
            assertEquals(1, server.snapshotCount(), "the current snapshot remains available")
        } finally {
            server.stop()
        }
    }

    @Test
    fun updateRejectsAHashThatDoesNotDescribeTheBytes() {
        val server = server()
        assertFailsWith<IllegalArgumentException> {
            server.updatePack("actual".toByteArray(), PackBuilder.computeSha1("different".toByteArray()))
        }
    }

    @Test
    fun packConfigurationRejectsInvalidPublicValues() {
        assertFailsWith<IllegalArgumentException> { PackConfig(port = 65_536) }
        assertFailsWith<IllegalArgumentException> { PackConfig(namespace = "Not Valid") }
        assertFailsWith<IllegalArgumentException> { PackConfig(publicAddress = "https://example.test") }
        assertFailsWith<IllegalArgumentException> {
            PackConfig(presentationWaitTimeoutMillis = 999L)
        }
    }

    @Test
    fun aBindFailureIsActionableAndTheServerCanBeRetried() {
        val occupied = ServerSocket().apply {
            bind(InetSocketAddress("127.0.0.1", 0))
        }
        val port = occupied.localPort
        val logger = Logger.getLogger("PackServerSnapshotTest-bind").apply { level = Level.OFF }
        val server = PackServer(
            PackConfig(
                port = port,
                bindAddress = "127.0.0.1",
                publicAddress = "127.0.0.1",
            ),
            logger,
        )
        try {
            val failure = assertFailsWith<IllegalStateException> { server.start() }
            assertTrue(failure.message.orEmpty().contains("displaykit.pack.port"))
        } finally {
            occupied.close()
            server.stop()
        }

        server.start()
        assertTrue(server.isRunning())
        server.stop()
    }

    private fun server(): PackServer {
        val logger = Logger.getLogger("PackServerSnapshotTest").apply { level = Level.OFF }
        return PackServer(
            PackConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                publicAddress = "127.0.0.1",
            ),
            logger,
        )
    }

    private fun download(url: String): ByteArray {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, response.statusCode())
        return response.body()
    }
}
