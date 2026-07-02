package io.schemat.displaykit.pack

data class PackConfig(
    val port: Int = System.getProperty("displaykit.pack.port")?.toIntOrNull() ?: 8080,
    val bindAddress: String = "0.0.0.0",
    val publicAddress: String = System.getProperty("displaykit.pack.address") ?: "localhost",
    val namespace: String = "displaykit",
    val packFormat: Int = 75,  // MC 1.21.11
    val autoSendOnJoin: Boolean = true,
    val packDescription: String = "DisplayKit Resource Pack",
    val registerDefaultProviders: Boolean = true
)
