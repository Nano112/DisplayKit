package io.schemat.displaykit.pack

data class PackConfig(
    /** HTTP port. Use 0 to let the operating system select a free port. */
    val port: Int = System.getProperty("displaykit.pack.port")?.toIntOrNull() ?: 8080,
    /** Interface to listen on. Use 0.0.0.0 for players outside this host. */
    val bindAddress: String = "0.0.0.0",
    /** Hostname or IP placed in URLs sent to clients (without scheme or port). */
    val publicAddress: String = System.getProperty("displaykit.pack.address") ?: "localhost",
    val namespace: String = "displaykit",
    val packFormat: Int = 75,  // MC 1.21.11
    val autoSendOnJoin: Boolean = true,
    val packDescription: String = "DisplayKit Resource Pack",
    /**
     * Maximum quiet period while a presentation waits for pack consent,
     * download, or reload progress. ACCEPTED and DOWNLOADED responses renew
     * the full period. This is deliberately generous: heavily modded clients
     * can spend well over ten seconds in a legitimate resource reload.
     */
    val presentationWaitTimeoutMillis: Long =
        System.getProperty("displaykit.pack.presentationWaitTimeoutMillis")?.toLongOrNull()
            ?: 120_000L,
    /**
     * Whether to register [GeistFontProvider], which ships ~660KB of Geist TTFs
     * (the vast majority of the pack's total size) and writes
     * `assets/minecraft/font/default.json`, overriding Minecraft's DEFAULT font.
     * That silently restyles every piece of client-side text, not just DisplayKit UI.
     * Opt-in: leave this false unless you specifically want Geist as the client's font.
     */
    val registerGeistFont: Boolean = false
) {
    init {
        require(port in 0..65535) { "port must be in 0..65535" }
        require(bindAddress.isNotBlank()) { "bindAddress must not be blank" }
        require(publicAddress.isNotBlank()) { "publicAddress must not be blank" }
        require("://" !in publicAddress) { "publicAddress must not include a URL scheme" }
        require(namespace.matches(Regex("[a-z0-9_.-]+"))) {
            "namespace must contain only lowercase letters, digits, '_', '-', or '.'"
        }
        require(packFormat > 0) { "packFormat must be positive" }
        require(packDescription.isNotBlank()) { "packDescription must not be blank" }
        require(presentationWaitTimeoutMillis >= 1_000L) {
            "presentationWaitTimeoutMillis must be at least 1000"
        }
    }
}
