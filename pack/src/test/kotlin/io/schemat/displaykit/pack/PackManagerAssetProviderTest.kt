package io.schemat.displaykit.pack

import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Registration must be idempotent: callers like PickerWindow.open() call
 * registerAssetProvider() unconditionally on every invocation, so a provider
 * already present must not be appended again, and repeated rebuilds must not
 * re-run the same contribution once per stale duplicate.
 */
class PackManagerAssetProviderTest {

    /** Counts how many times it was asked to contribute, to prove the list didn't grow. */
    private class CountingProvider(private val path: String, private val content: String) : AssetProvider {
        var contributionCount = 0
            private set

        override fun contributeAssets(builder: PackBuilder) {
            contributionCount++
            builder.addText(path, content)
        }
    }

    private fun quietManager(): PackManager {
        val logger = Logger.getLogger("PackManagerAssetProviderTest").apply { level = Level.OFF }
        return PackManager(PackConfig(), logger)
    }

    @Test
    fun registeringTheSameProviderTwiceDoesNotGrowTheProviderList() {
        val manager = quietManager()
        val provider = CountingProvider("assets/displaykit/marker.txt", "hello")

        manager.registerAssetProvider(provider)
        manager.registerAssetProvider(provider)
        manager.registerAssetProvider(provider)
        manager.rebuildPack()

        // If the provider had been appended three times, rebuildPack would have
        // asked it to contribute three times. It must contribute exactly once.
        assertEquals(1, provider.contributionCount)
    }

    @Test
    fun repeatedRegistrationProducesAnIdenticalPackToRegisteringOnce() {
        val manager = quietManager()
        val provider = CountingProvider("assets/displaykit/marker.txt", "hello")

        manager.registerAssetProvider(provider)
        manager.rebuildPack()
        val singleRegistrationSha1 = manager.getPackSha1()

        // Register the same instance again (as an unconditional call site like
        // PickerWindow.open() would on a second invocation) and rebuild.
        manager.registerAssetProvider(provider)
        manager.registerAssetProvider(provider)
        manager.rebuildPack()
        val repeatedRegistrationSha1 = manager.getPackSha1()

        assertContentEquals(singleRegistrationSha1, repeatedRegistrationSha1)
    }

    @Test
    fun eachPushUsesTheImmutableUrlForItsAdvertisedHash() {
        val manager = quietManager()
        val sent = mutableListOf<Pair<String, ByteArray>>()
        manager.onSendPack = { _, url, sha1 -> sent += url to sha1 }
        val player = java.util.UUID.randomUUID()

        manager.registerAssetProvider(CountingProvider("assets/displaykit/a.txt", "a"))
        manager.rebuildPack()
        manager.sendToPlayer(player)

        manager.registerAssetProvider(CountingProvider("assets/displaykit/b.txt", "b"))
        manager.rebuildPack()
        manager.sendToPlayer(player)

        assertEquals(2, sent.size)
        assertNotEquals(sent[0].first, sent[1].first)
        for ((url, hash) in sent) {
            assertTrue(url.endsWith("/packs/${PackBuilder.sha1ToHex(hash)}.zip"))
        }

        // A stale completion must not complete the newer push.
        assertEquals(false, manager.onPackStatus(player, sent[0].second, PackManager.PackStatus.ACCEPTED))
        assertEquals(PackManager.PackStatus.SENDING, manager.getPlayerStatus(player))
        assertEquals(true, manager.onPackStatus(player, sent[1].second, PackManager.PackStatus.ACCEPTED))
        assertEquals(PackManager.PackStatus.ACCEPTED, manager.getPlayerStatus(player))
    }

    @Test
    fun platformSendFailureIsReportedWithoutEscapingTheServerEvent() {
        val manager = quietManager()
        val player = java.util.UUID.randomUUID()
        manager.registerAssetProvider(CountingProvider("assets/displaykit/a.txt", "a"))
        manager.rebuildPack()
        manager.onSendPack = { _, _, _ -> error("connection disappeared") }

        assertFalse(manager.sendToPlayer(player))
        assertEquals(PackManager.PackStatus.FAILED, manager.getPlayerStatus(player))
    }

    @Test
    fun duplicatePushOfTheSameArtifactIsCoalesced() {
        val manager = quietManager()
        val player = java.util.UUID.randomUUID()
        var sends = 0
        manager.registerAssetProvider(CountingProvider("assets/displaykit/a.txt", "a"))
        manager.rebuildPack()
        manager.onSendPack = { _, _, _ -> sends++ }

        assertTrue(manager.sendToPlayer(player))
        assertTrue(manager.sendToPlayer(player))

        assertEquals(1, sends)
        assertEquals(PackManager.PackStatus.SENDING, manager.getPlayerStatus(player))
    }

    @Test
    fun responseDeadlineReleasesAllInFlightArtifacts() {
        val manager = quietManager()
        val player = java.util.UUID.randomUUID()
        val sent = mutableListOf<ByteArray>()
        manager.onSendPack = { _, _, sha1 -> sent += sha1 }
        manager.registerAssetProvider(CountingProvider("assets/displaykit/a.txt", "a"))
        manager.rebuildPack()
        manager.sendToPlayer(player)
        manager.registerAssetProvider(CountingProvider("assets/displaykit/b.txt", "b"))
        manager.rebuildPack()
        manager.sendToPlayer(player)

        manager.failInFlight(player)

        assertEquals(PackManager.PackStatus.FAILED, manager.getPlayerStatus(player))
        assertFalse(manager.onPackStatus(player, sent[0], PackManager.PackStatus.ACCEPTED))
        assertFalse(manager.onPackStatus(player, sent[1], PackManager.PackStatus.ACCEPTED))
    }
}
