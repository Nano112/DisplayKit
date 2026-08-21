package io.schemat.displaykit.pack

import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
        return PackManager(PackConfig(registerDefaultProviders = false), logger)
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
}
